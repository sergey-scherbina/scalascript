package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class BasicAuthTest extends AnyFunSuite with Matchers:

  private def encode(user: String, pass: String): String =
    val raw = s"$user:$pass".getBytes("UTF-8")
    "Basic " + java.util.Base64.getEncoder.encodeToString(raw)

  test("fromHeader — valid header decodes to (user, password)") {
    BasicAuth.fromHeader(encode("alice", "secret")) shouldBe Some(("alice", "secret"))
  }

  test("fromHeader — password may contain colons (only the first one splits)") {
    BasicAuth.fromHeader(encode("u", "p:a:s:s")) shouldBe Some(("u", "p:a:s:s"))
  }

  test("fromHeader — empty username or password is preserved") {
    BasicAuth.fromHeader(encode("", "p")) shouldBe Some(("", "p"))
    BasicAuth.fromHeader(encode("u", ""))  shouldBe Some(("u", ""))
  }

  test("fromHeader — scheme match is case-insensitive") {
    BasicAuth.fromHeader("basic " + java.util.Base64.getEncoder.encodeToString("a:b".getBytes("UTF-8"))) shouldBe Some(("a", "b"))
    BasicAuth.fromHeader("BASIC " + java.util.Base64.getEncoder.encodeToString("a:b".getBytes("UTF-8"))) shouldBe Some(("a", "b"))
  }

  test("fromHeader — non-Basic schemes return None") {
    BasicAuth.fromHeader("Bearer abc.def.ghi") shouldBe None
    BasicAuth.fromHeader("Digest …")           shouldBe None
  }

  test("fromHeader — null and short / empty input return None without throwing") {
    BasicAuth.fromHeader(null) shouldBe None
    BasicAuth.fromHeader("")   shouldBe None
    BasicAuth.fromHeader("B")  shouldBe None
  }

  test("fromHeader — invalid base64 returns None without throwing") {
    BasicAuth.fromHeader("Basic !!!not-base64!!!") shouldBe None
  }

  test("fromHeader — decoded payload without `:` returns None") {
    val noColon = "Basic " + java.util.Base64.getEncoder.encodeToString("nocolonhere".getBytes("UTF-8"))
    BasicAuth.fromHeader(noColon) shouldBe None
  }
