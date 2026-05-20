package scalascript.server.jvm

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.server.Request

/** Tests for the `validate { … }` accumulator + `requireString` /
 *  `requireInt` / `requireRange` helpers that RestRuntime.scala
 *  defines.  Runtime contract: `validate` returns `Any` typed as
 *  `Either[Map[String, String], A]` at runtime, so tests pattern-
 *  match with explicit type ascriptions. */
class ValidateTest extends AnyFunSuite with Matchers:

  private def req(form: Map[String, String] = Map.empty,
                  query: Map[String, String] = Map.empty): Request =
    Request(method = "POST", path = "/", params = Map.empty,
            query = query, headers = Map.empty, body = "",
            form = form)

  private def expectErrors(result: Any, keys: String*): Unit =
    result match
      case Left(errs: Map[String, String] @unchecked) =>
        keys.foreach { k => errs.keys.toSet.contains(k) shouldBe true }
      case _ => fail(s"expected Left with errors $keys, got $result")

  test("validate — happy path returns Right with body value") {
    val r = req(form = Map("email" -> "ada@example.com", "rating" -> "4"))
    val result = validate {
      val email  = requireString(r, "email")
      val rating = requireRange(r, "rating", 1L, 5L)
      (email, rating)
    }
    result shouldBe Right(("ada@example.com", 4L))
  }

  test("validate — accumulated errors return Left with all violations") {
    val r = req(form = Map("rating" -> "9"))
    val result = validate {
      val email  = requireString(r, "email")             // missing
      val rating = requireRange(r, "rating", 1L, 5L)     // out of range
      (email, rating)
    }
    expectErrors(result, "email", "rating")
  }

  test("validate — missing required field accumulates error") {
    val r = req()
    val result = validate { requireString(r, "name") }
    expectErrors(result, "name")
  }

  test("validate — query is consulted if form is missing the key") {
    val r = req(query = Map("token" -> "abc"))
    val result = validate { requireString(r, "token") }
    result shouldBe Right("abc")
  }

  test("requireInt — non-numeric value triggers error") {
    val r = req(form = Map("n" -> "not-a-number"))
    val result = validate { requireInt(r, "n") }
    expectErrors(result, "n")
  }

  test("requireRange — boundary values accepted") {
    val r1 = req(form = Map("v" -> "1"))
    val r5 = req(form = Map("v" -> "5"))
    validate { requireRange(r1, "v", 1L, 5L) } shouldBe Right(1L)
    validate { requireRange(r5, "v", 1L, 5L) } shouldBe Right(5L)
  }
