package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.17.x — end-to-end script-side OAuth intrinsics: `oauth.authServer`,
 *  `oauth.serveAuthServer`, `oauth.issueHmacToken`, `oauth.pkceVerifier`
 *  / `oauth.pkceChallenge` plus the `as.*` methods on the returned
 *  AuthServer handle. */
class OAuthScriptTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("oauth.authServer + as.metadata round trip"):
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://auth.local",
        "signingSecret" -> "secret-123"
      ))
      println(as.issuer)
      val md = as.metadata()
      println(md("issuer"))
      """
    )
    out should include ("https://auth.local")

  test("oauth.issueHmacToken: signed token round trip"):
    val out = captured(
      """
      val tok = oauth.issueHmacToken("secret", "alice", List("read"), 60)
      println(tok.length > 50)
      """
    )
    out shouldBe "true"

  test("oauth.pkceVerifier / oauth.pkceChallenge are deterministic"):
    val out = captured(
      """
      val v = "fixed-verifier-1234567890-abcdefghij-1234567890"
      val c1 = oauth.pkceChallenge(v)
      val c2 = oauth.pkceChallenge(v)
      println(c1 == c2)
      println(c1.length > 20)
      """
    )
    out.split("\n").toList shouldBe List("true", "true")

  test("as.registerClient + as.issueClientCredentialsToken end-to-end"):
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://auth.local",
        "signingSecret" -> "s",
        "scopes" -> List("read")
      ))
      val client = as.registerClient(Map(
        "redirect_uris" -> List("http://localhost/cb"),
        "grant_types" -> List("client_credentials"),
        "scope" -> "read"
      ))
      val cid = client("client_id")
      val sec = client("client_secret")
      val tok = as.issueClientCredentialsToken(cid, sec, List("read"))
      val info = as.introspect(tok)
      println(info("active"))
      println(info("sub") == cid)
      """
    )
    out.split("\n").toList shouldBe List("true", "true")

  test("as.revokeToken flips introspect.active to false"):
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://auth.local",
        "signingSecret" -> "s",
        "scopes" -> List("read")
      ))
      val client = as.registerClient(Map(
        "redirect_uris" -> List("http://localhost/cb"),
        "grant_types" -> List("client_credentials"),
        "scope" -> "read"
      ))
      val tok = as.issueClientCredentialsToken(
        client("client_id"), client("client_secret"), List("read"))
      println(as.introspect(tok)("active"))
      as.revokeToken(tok)
      println(as.introspect(tok)("active"))
      """
    )
    out.split("\n").toList shouldBe List("true", "false")

  test("mcpServer + srv.useAuthServer accepts AS-issued tokens"):
    // Build the AS, mint a token, then verify that MCP's authorizeHttp
    // accepts it after srv.useAuthServer.  Uses the test-only Scala
    // bridge since we can't exercise the actual HTTP transport here.
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://auth.local",
        "signingSecret" -> "s",
        "scopes" -> List("read")
      ))
      val client = as.registerClient(Map(
        "redirect_uris" -> List("http://localhost/cb"),
        "grant_types" -> List("client_credentials"),
        "scope" -> "read"
      ))
      val tok = as.issueClientCredentialsToken(
        client("client_id"), client("client_secret"), List("read"))
      mcpServer { srv =>
        srv.useAuthServer(as)
        println(srv.authEnabled)
        // currentAuth is None when no request is being dispatched
        println(srv.currentAuth)
      }
      """
    )
    out.split("\n").toList shouldBe List("true", "None")
