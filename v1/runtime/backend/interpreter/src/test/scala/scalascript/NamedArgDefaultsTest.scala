package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Partial named-arg construction of a case class with default fields must bind
 *  by NAME, filling omitted fields with their defaults — not positionally.  The
 *  case-class ctor is a NativeFnV that drops argument names, so `S(a = .., c = ..)`
 *  used to mis-place `c`'s value into field `b`.  Surfaced building std.ui `Style`. */
class NamedArgDefaultsTest extends AnyFunSuite:

  private def run(body: String): String =
    val src = "# T\n\n```scalascript\n" + body + "\n```\n"
    val b = java.io.ByteArrayOutputStream(); val ps = java.io.PrintStream(b, true)
    interpreter.Interpreter(ps).run(Parser.parse(src))
    ps.flush(); b.toString.trim

  private val decl =
    "case class S(a: Option[String] = None, b: Option[Int] = None, c: Option[String] = None)\n"
  private def opt(field: String): String =
    s"println(s.$field match { case Some(_) => \"some\"; case None => \"none\" })"

  test("gap: S(a=.., c=..) leaves b at its default None"):
    assert(run(decl + "val s = S(a = Some(\"x\"), c = Some(\"z\"))\n" + opt("b")) == "none")
    assert(run(decl + "val s = S(a = Some(\"x\"), c = Some(\"z\"))\n" + opt("a")) == "some")
    assert(run(decl + "val s = S(a = Some(\"x\"), c = Some(\"z\"))\n" + opt("c")) == "some")

  test("single named field, rest default"):
    assert(run(decl + "val s = S(b = Some(7))\n" + opt("a")) == "none")
    assert(run(decl + "val s = S(b = Some(7))\n" + opt("b")) == "some")
    assert(run(decl + "val s = S(b = Some(7))\n" + opt("c")) == "none")

  test("reordered named args bind by name, not position"):
    assert(run(decl + "val s = S(c = Some(\"z\"), a = Some(\"x\"))\n" +
      "println(s.a.getOrElse(\"?\") + \",\" + s.c.getOrElse(\"?\"))") == "x,z")

  test("all named (already worked) still correct"):
    assert(run(decl + "val s = S(a = Some(\"x\"), b = Some(1), c = Some(\"z\"))\n" + opt("b")) == "some")

  test("default value can reference an earlier provided field"):
    val d = "case class P(x: Int, y: Int = 0, label: String = \"pt\")\n"
    assert(run(d + "val p = P(x = 5)\n" + "println(\"\" + p.x + \"/\" + p.y + \"/\" + p.label)") == "5/0/pt")
