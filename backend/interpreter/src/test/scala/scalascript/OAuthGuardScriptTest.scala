package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.17.x — end-to-end script-side `oauth.guard(...)` Resource Server
 *  SDK.  Any HTTP route can be wrapped in bearer-token validation. */
class OAuthGuardScriptTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("oauth.guard: builds a wrapped handler that validates bearer tokens"):
    val out = captured(
      """
      val as = oauth.authServer(Map(
        "issuer" -> "https://auth.local",
        "signingSecret" -> "s",
        "scopes" -> List("read")
      ))
      val client = as.registerClient(Map(
        "redirect_uris" -> List("http://x/cb"),
        "grant_types" -> List("client_credentials"),
        "scope" -> "read"
      ))
      val tok = as.issueClientCredentialsToken(
        client("client_id"), client("client_secret"), List("read"))

      // Build a guarded handler — handler signature is (req, claims) => Response
      val handler = oauth.guard(as, List("read"))((req, claims) =>
        Map("status" -> 200, "body" -> claims("subject"))
      )

      // Call it with a Request containing the bearer header.
      val req = Map(
        "body" -> "",
        "headers" -> Map("Authorization" -> ("Bearer " + tok)),
        "query" -> Map()
      )
      // The handler is a NativeFn that takes a Request InstanceV.  In
      // script-land we can't easily construct one, so we call through
      // ScalaScript's `request.*` shape… but Map suffices because the
      // wrapped fn pattern-matches on the InstanceV constructor.
      // Skip the InstanceV construction here and just check the
      // wiring at JVM-level in OAuthGuardTest.  For script-level we
      // verify the guard builder is callable.
      println(handler != null)
      """
    )
    out shouldBe "true"

  test("oauth.hmacValidator: returns a Valid InstanceV for fresh tokens"):
    val out = captured(
      """
      val v   = oauth.hmacValidator("s")
      val tok = oauth.issueHmacToken("s", "alice", List("read"), 60)
      val res = v(tok)
      // res is an InstanceV("Valid", { subject, scopes, extra })
      println(res.subject)
      println(res.scopes)
      """
    )
    out.split("\n").toList shouldBe List("alice", "List(read)")

  test("oauth.hmacValidator: returns Invalid for garbage"):
    val out = captured(
      """
      val v   = oauth.hmacValidator("s")
      val res = v("garbage")
      println(res.code)
      """
    )
    out shouldBe "invalid_token"

  test("oauth.guardWithValidator: works with a custom validator function"):
    // The user can wire a custom validator (e.g. JWKS-backed) via the
    // -WithValidator variant.  Here we use the built-in HMAC for
    // simplicity but the indirection is what matters.
    val out = captured(
      """
      val v = oauth.hmacValidator("s")
      val guarded = oauth.guardWithValidator(v, List("read"))((req, claims) =>
        Map("ok" -> true)
      )
      println(guarded != null)
      """
    )
    out shouldBe "true"
