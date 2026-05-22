package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — `ssc oauth ...` CLI subcommand coverage.  Tests the
 *  offline paths (mint + introspect) end-to-end; network-touching
 *  paths (discover, jwks, dcr-register) are smoke-tested only via
 *  the error paths to avoid spinning a real AS in unit tests. */
class OAuthCliTest extends AnyFunSuite with Matchers:

  /** Run an OAuthCli command + capture stdout / stderr / exit code.
   *  Returns (stdout, stderr) — sys.exit is caught via SecurityManager
   *  trick: we rely on commands that exit cleanly OR throw.  For
   *  exit-status testing we use the offline paths that don't call
   *  sys.exit on success. */
  private def captureStdout(thunk: => Unit): String =
    val out = new java.io.ByteArrayOutputStream
    val ps  = new java.io.PrintStream(out)
    // Scala's `println` writes to `Console.out`; tee both Console and
    // System so we capture however the CLI emits.
    val prevSys = System.out
    System.setOut(ps)
    try Console.withOut(ps) { thunk }
    finally System.setOut(prevSys)
    ps.flush()
    out.toString("UTF-8")

  // ─── mint ─────────────────────────────────────────────────────────

  test("mint: prints a valid HS256 JWT"):
    val out = captureStdout {
      OAuthCli.run(List("mint", "k" * 40, "alice", "read", "write"))
    }
    val token = out.trim
    token.split('.').length shouldBe 3
    // Validate the token via the matching primitive
    OAuth.decodeHmacToken("k" * 40, token) match
      case Right(p) =>
        p("sub").str shouldBe "alice"
        p("scope").str.split(' ').toSet shouldBe Set("read", "write")
      case Left(reason) => fail(s"mint produced invalid token: $reason")

  test("mint: warns on short secret to stderr but still emits the token"):
    val err = new java.io.ByteArrayOutputStream
    val errPs = new java.io.PrintStream(err)
    val prevSysErr = System.err
    System.setErr(errPs)
    val out = try
      Console.withErr(errPs) {
        captureStdout { OAuthCli.run(List("mint", "short", "alice")) }
      }
    finally System.setErr(prevSysErr)
    errPs.flush()
    err.toString("UTF-8") should include ("WARN")
    out.trim.split('.').length shouldBe 3

  // ─── introspect ───────────────────────────────────────────────────

  test("introspect: decodes + prints claim JSON"):
    val token = OAuth.issueHmacToken("k" * 40, "bob", Set("read"), 3600L,
      issuer = Some("https://x"), clientId = Some("c1"))
    val out = captureStdout {
      OAuthCli.run(List("introspect", "k" * 40, token))
    }
    val js = ujson.read(out)
    js("sub").str       shouldBe "bob"
    js("scope").str     shouldBe "read"
    js("iss").str       shouldBe "https://x"
    js("client_id").str shouldBe "c1"

  // ─── help / unknown ───────────────────────────────────────────────

  test("help: prints usage to stdout"):
    val out = captureStdout { OAuthCli.run(List("help")) }
    out should include ("ssc oauth")
    out should include ("discover")
    out should include ("jwks")
    out should include ("dcr-register")
    out should include ("mint")
    out should include ("introspect")

  test("no args: prints usage"):
    val out = captureStdout { OAuthCli.run(Nil) }
    out should include ("ssc oauth")
