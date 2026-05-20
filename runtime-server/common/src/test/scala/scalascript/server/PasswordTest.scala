package scalascript.server

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PasswordTest extends AnyFunSuite with Matchers:

  // Use iter=1 to keep tests fast; the algorithm is identical at any iteration count.
  private val FastIter = 1

  test("hash / verify — correct password accepted") {
    val h = Password.hash("correct-horse-battery-staple", FastIter)
    Password.verify("correct-horse-battery-staple", h) shouldBe true
  }

  test("hash / verify — wrong password rejected") {
    val h = Password.hash("secret", FastIter)
    Password.verify("wrong", h) shouldBe false
  }

  test("hash — output format is pbkdf2$iter=N$salt$hash") {
    val h = Password.hash("pw", FastIter)
    h should startWith("pbkdf2$iter=1$")
    h.split('$').length shouldBe 4
  }

  test("hash — two calls produce different salts") {
    val h1 = Password.hash("pw", FastIter)
    val h2 = Password.hash("pw", FastIter)
    h1 should not equal h2
  }

  test("verify — empty password handled correctly") {
    val h = Password.hash("", FastIter)
    Password.verify("", h) shouldBe true
    Password.verify("notempty", h) shouldBe false
  }

  test("verify — malformed encoded string returns false, no exception") {
    Password.verify("pw", "not-a-valid-hash") shouldBe false
    Password.verify("pw", "") shouldBe false
    Password.verify("pw", "pbkdf2$iter=bad$salt$hash") shouldBe false
  }

  test("verify — corrupted salt returns false") {
    val h     = Password.hash("pw", FastIter)
    val parts = h.split('$')
    // Replace the salt (index 2) with zeros — same length, different bytes.
    val badSalt   = java.util.Base64.getEncoder.withoutPadding.encodeToString(Array.fill[Byte](16)(0))
    val corrupted = s"${parts(0)}$$${parts(1)}$$$badSalt$$${parts(3)}"
    Password.verify("pw", corrupted) shouldBe false
  }
