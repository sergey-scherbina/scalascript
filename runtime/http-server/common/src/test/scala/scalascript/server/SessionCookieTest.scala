package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SessionCookieTest extends AnyFunSuite with Matchers:

  test("pack / unpack — round-trip preserves payload") {
    val payload = Map("userId" -> "42", "role" -> "admin")
    SessionCookie.unpack(SessionCookie.pack(payload)) shouldBe Some(payload)
  }

  test("pack / unpack — empty payload round-trips") {
    SessionCookie.unpack(SessionCookie.pack(Map.empty)) shouldBe Some(Map.empty)
  }

  test("unpack — tampered body returns None") {
    val cookie = SessionCookie.pack(Map("user" -> "alice"))
    val idx    = cookie.indexOf('.')
    val tampered = "AAAAAAAAAA" + cookie.substring(idx)
    SessionCookie.unpack(tampered) shouldBe None
  }

  test("unpack — tampered signature returns None") {
    val cookie = SessionCookie.pack(Map("user" -> "alice"))
    val tampered = cookie.dropRight(4) + "XXXX"
    SessionCookie.unpack(tampered) shouldBe None
  }

  test("unpack — empty string returns None") {
    SessionCookie.unpack("") shouldBe None
  }

  test("unpack — no dot separator returns None") {
    SessionCookie.unpack("nodothere") shouldBe None
  }

  test("pack / unpack — special characters in values survive") {
    val payload = Map("msg" -> "hello\nworld", "q" -> "\"quoted\"")
    SessionCookie.unpack(SessionCookie.pack(payload)) shouldBe Some(payload)
  }

  test("fromHeader — extracts session from cookie header") {
    val packed = SessionCookie.pack(Map("x" -> "1"))
    val header = s"foo=bar; session=$packed; baz=qux"
    SessionCookie.fromHeader(header) shouldBe Some(Map("x" -> "1"))
  }

  test("fromHeader — missing session cookie returns None") {
    SessionCookie.fromHeader("foo=bar; baz=qux") shouldBe None
  }

  test("toSetCookie — non-empty payload includes packed value") {
    val payload = Map("u" -> "bob")
    val header  = SessionCookie.toSetCookie(payload)
    header should startWith("session=")
    header should include("HttpOnly")
    header should include("SameSite=")
    header should not include "Max-Age=0"
  }

  test("toSetCookie — empty payload clears cookie") {
    SessionCookie.toSetCookie(Map.empty) should include("Max-Age=0")
  }
