package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Interpreter support for `Set` (SetV). */
class SetTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("construction: Set(...), Set[T](), Set.empty, dedup"):
    run("""
      println(Set(1, 2, 3).size)
      println(Set(1, 1, 2).size)
      println(Set[String]().isEmpty)
      println(Set.empty.isEmpty)
    """) shouldBe "3\n2\ntrue\ntrue"

  test("contains / + / - "):
    run("""
      val s = Set("a", "b")
      println(s.contains("a"))
      println(s.contains("z"))
      println((s + "c").size)
      println((s - "a").contains("a"))
    """) shouldBe "true\nfalse\n3\nfalse"

  test("union ++, intersect &, diff --"):
    run("""
      val a = Set(1, 2, 3)
      val b = Set(2, 3, 4)
      println((a ++ b).size)
      println((a & b).size)
      println((a -- b).size)
      println(a.subsetOf(Set(1, 2, 3, 4)))
    """) shouldBe "4\n2\n1\ntrue"

  test("list.toSet returns a Set; folds work"):
    run("""
      val s = List(1, 2, 2, 3, 3, 3).toSet
      println(s.size)
      println(s.toList.sorted)
      println(s.exists(x => x > 2))
      println(s.foldLeft(0)((a, b) => a + b))
    """) shouldBe "3\nList(1, 2, 3)\ntrue\n6"

  test("map / filter return Sets"):
    run("""
      println(Set(1, 2, 3).map(x => x * 2).size)
      println(Set(1, 2, 3, 4).filter(x => x % 2 == 0).size)
    """) shouldBe "3\n2"

  test("deterministic show (sorted)"):
    run("""println(Set(3, 1, 2))""") shouldBe "Set(1, 2, 3)"

  test("set equality is by value, order-independent"):
    run("""
      println(Set(1, 2, 3) == Set(3, 2, 1))
      println(Set(1, 2) == Set(1, 2, 3))
    """) shouldBe "true\nfalse"

  test("Set round-trips through the value serializer"):
    val v = scalascript.interpreter.Value.SetV(Set(
      scalascript.interpreter.Value.intV(1),
      scalascript.interpreter.Value.intV(2)))
    val json = scalascript.interpreter.ValueSerializer.serialize(v)
    scalascript.interpreter.ValueSerializer.deserialize(json) shouldBe v
