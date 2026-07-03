package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

class OpticsRuntimeTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("optional optics treat None as absent after OptionV null-sentinel optimization") {
    captured("""
      case class Address(street: String, city: String)
      case class Profile(home: Option[Address])
      case class User(handle: String, profile: Option[Profile])

      val withHome = User("alice", Some(Profile(Some(Address("Main", "Boston")))))
      val withoutProfile = User("bob", None)
      val withoutHome = User("carol", Some(Profile(None)))
      val cityOpt = Focus[User](_.profile.some.home.some.city)

      println(cityOpt.getOption(withHome))
      println(cityOpt.getOption(withoutProfile))
      println(cityOpt.getOption(withoutHome))
      println(cityOpt.set(withoutProfile, "Paris").profile)
      println(cityOpt.modify(withoutHome, c => c.toUpperCase).profile)
    """) shouldBe "Some(Boston)\nNone\nNone\nNone\nSome(Profile(None))"
  }
