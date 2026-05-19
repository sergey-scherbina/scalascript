package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JwtTest extends AnyFunSuite with Matchers:

  test("sign / verify — round-trip preserves all claims") {
    val claims = Map("sub" -> "user-42", "role" -> "admin")
    Jwt.verify(Jwt.sign(claims)) shouldBe Some(claims)
  }

  test("verify — tampered signature returns None") {
    val token = Jwt.sign(Map("sub" -> "alice"))
    val parts = token.split('.')
    val bad   = parts(0) + "." + parts(1) + ".invalidsig"
    Jwt.verify(bad) shouldBe None
  }

  test("verify — tampered payload returns None") {
    val token = Jwt.sign(Map("sub" -> "alice"))
    val parts = token.split('.')
    val fakePayload = java.util.Base64.getUrlEncoder.withoutPadding
      .encodeToString("""{"sub":"eve"}""".getBytes("UTF-8"))
    Jwt.verify(parts(0) + "." + fakePayload + "." + parts(2)) shouldBe None
  }

  test("verify — expired token (exp in the past) returns None") {
    val token = Jwt.sign(Map("sub" -> "alice", "exp" -> "1"))
    Jwt.verify(token) shouldBe None
  }

  test("verify — future exp is accepted") {
    val farFuture = (java.lang.System.currentTimeMillis() / 1000L + 3600).toString
    val token = Jwt.sign(Map("sub" -> "alice", "exp" -> farFuture))
    Jwt.verify(token).map(_("sub")) shouldBe Some("alice")
  }

  test("verify — malformed token (wrong number of parts) returns None") {
    Jwt.verify("not.a.valid.jwt.token") shouldBe None
    Jwt.verify("onlyone") shouldBe None
    Jwt.verify("") shouldBe None
  }

  test("verify — non-numeric exp returns None") {
    val token = Jwt.sign(Map("exp" -> "notanumber"))
    Jwt.verify(token) shouldBe None
  }

  test("sign / verify — empty claims round-trip") {
    Jwt.verify(Jwt.sign(Map.empty)) shouldBe Some(Map.empty)
  }

  test("sign / verify — claims with special characters") {
    val claims = Map("msg" -> "hello\nworld\t!", "q" -> "\"quoted\"")
    Jwt.verify(Jwt.sign(claims)) shouldBe Some(claims)
  }

  test("fromAuthHeader — extracts bearer token") {
    Jwt.fromAuthHeader("Bearer mytoken123") shouldBe Some("mytoken123")
  }

  test("fromAuthHeader — case-insensitive Bearer prefix") {
    Jwt.fromAuthHeader("bearer mytoken") shouldBe Some("mytoken")
    Jwt.fromAuthHeader("BEARER mytoken") shouldBe Some("mytoken")
  }

  test("fromAuthHeader — non-bearer header returns None") {
    Jwt.fromAuthHeader("Basic dXNlcjpwYXNz") shouldBe None
  }

  test("fromAuthHeader — null / empty returns None") {
    Jwt.fromAuthHeader(null) shouldBe None
    Jwt.fromAuthHeader("") shouldBe None
  }
