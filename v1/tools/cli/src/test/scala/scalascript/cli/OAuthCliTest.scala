package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.oauth.*

/** v1.17.x — `ssc oauth ...` CLI subcommand coverage.  Tests the
 *  offline paths (mint + introspect) end-to-end; network-touching
 *  paths (discover, jwks, dcr-register) are smoke-tested only via
 *  the error paths to avoid spinning a real AS in unit tests.
 *
 *  Every FAILURE path is asserted through `OAuthCli.status`, which returns an exit code instead of
 *  calling `sys.exit`.  That is not a style preference: while these helpers exited directly, calling
 *  one from a test killed the forked test JVM, and the run reported "All tests passed." for every
 *  suite with no `*** FAILED ***` anywhere, ending in a bare `ForkMain … exit code 1`
 *  (BUGS.md `cli-command-System.exit-kills-the-test-fork`).  This file's own header used to say it
 *  avoided those paths for exactly that reason — the defect had shaped the tests around itself, so
 *  the untested paths were precisely the ones that could expose it.  The cases below are the ones
 *  that were impossible before. */
class OAuthCliTest extends AnyFunSuite with Matchers:

  /** Capture stdout while running `thunk`. */
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

  // ─── failure paths: a status, not a dead JVM ──────────────────────
  //
  // Each case below reaches a branch that used to call `sys.exit`. If any of them regresses to an
  // exit, this suite does not fail — the whole forked JVM dies and the run reports success with a
  // trailing `ForkMain … exit code`. `scripts/detect-fork-exit` recognises that signature in CI;
  // these asserts are what stop it happening in the first place.

  /** Run a status-returning command, capturing stdout and stderr alongside the code. */
  private def statusOf(args: List[String]): (Int, String, String) =
    val out = new java.io.ByteArrayOutputStream
    val err = new java.io.ByteArrayOutputStream
    val outPs = new java.io.PrintStream(out)
    val errPs = new java.io.PrintStream(err)
    val prevOut = System.out
    val prevErr = System.err
    System.setOut(outPs); System.setErr(errPs)
    val rc =
      try Console.withOut(outPs) { Console.withErr(errPs) { OAuthCli.status(args) } }
      finally { System.setOut(prevOut); System.setErr(prevErr) }
    outPs.flush(); errPs.flush()
    (rc, out.toString("UTF-8"), err.toString("UTF-8"))

  test("unknown subcommand: status 2 with usage, and the JVM survives"):
    val (rc, out, err) = statusOf(List("nope"))
    rc shouldBe 2
    err should include ("Unknown subcommand: nope")
    out should include ("ssc oauth")

  test("mint: missing arguments are a usage error, not an exit"):
    val (rc, _, err) = statusOf(List("mint", "only-one"))
    rc shouldBe 2
    err should include ("ssc oauth mint")

  test("introspect: missing arguments are a usage error, not an exit"):
    val (rc, _, err) = statusOf(List("introspect", "only-one"))
    rc shouldBe 2
    err should include ("ssc oauth introspect")

  test("introspect: a token that does not verify is status 1, not an exit"):
    val good = OAuth.issueHmacToken("k" * 40, "bob", Set("read"), 3600L)
    val (rc, _, err) = statusOf(List("introspect", "j" * 40, good))
    rc shouldBe 1
    err should include ("introspect failed")

  test("discover: no issuer is a usage error, not an exit"):
    val (rc, _, err) = statusOf(List("discover"))
    rc shouldBe 2
    err should include ("ssc oauth discover")

  test("jwks: no issuer is a usage error, not an exit"):
    val (rc, _, err) = statusOf(List("jwks"))
    rc shouldBe 2
    err should include ("ssc oauth jwks")

  test("dcr-register: too few arguments is a usage error, not an exit"):
    val (rc, _, err) = statusOf(List("dcr-register", "https://issuer"))
    rc shouldBe 2
    err should include ("ssc oauth dcr-register")

  // The success side, so the suite cannot pass by returning a non-zero code for everything.
  test("success paths return status 0"):
    statusOf(List("mint", "k" * 40, "alice"))._1 shouldBe 0
    statusOf(List("help"))._1                    shouldBe 0
    statusOf(Nil)._1                             shouldBe 0
