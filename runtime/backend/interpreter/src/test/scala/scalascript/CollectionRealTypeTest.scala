package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** collection-real-type — the interpreter uses REAL Scala collection semantics, not just a display
 *  tag: `Array` is mutable + reference-identity, `LazyList` is lazy (infinite streams), and the eager
 *  types (`Vector`/`IndexedSeq`/`Seq`/`Iterable`) keep their display type with `List`-equivalent
 *  semantics. (spec: specs/collection-real-type.md) */
class CollectionRealTypeTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── eager types: display tag + List-equivalent semantics ──────────────────

  test("Vector renders as Vector and map preserves the type"):
    captured("println(Vector(1, 2, 3))") shouldBe "Vector(1, 2, 3)"
    captured("println(Vector(1, 2, 3).map(x => x * 2))") shouldBe "Vector(2, 4, 6)"

  test("IndexedSeq renders as Vector (its default impl)"):
    captured("println(IndexedSeq(1, 2, 3))") shouldBe "Vector(1, 2, 3)"

  test("Seq / Iterable render as List (Scala's default Seq IS List)"):
    captured("println(Seq(1, 2, 3))") shouldBe "List(1, 2, 3)"
    captured("println(Iterable(1, 2, 3))") shouldBe "List(1, 2, 3)"

  test("Vector == List is true (structural Seq equality, as in Scala)"):
    captured("println(Vector(1, 2, 3) == List(1, 2, 3))") shouldBe "true"

  test("toVector converts a List to Vector display; original List unchanged"):
    captured("val xs = List(1, 2, 3); val v = xs.toVector; println(v); println(xs)") shouldBe
      "Vector(1, 2, 3)\nList(1, 2, 3)"

  // ── Array: real mutable + reference identity ──────────────────────────────

  test("Array index update mutates in place"):
    captured("val a = Array(1, 2, 3); a(0) = 9; println(a(0))") shouldBe "9"

  test("Array.update method mutates in place"):
    captured("val a = Array(1, 2, 3); a.update(1, 8); println(a(1))") shouldBe "8"

  test("Array has reference identity: distinct arrays are not =="):
    captured("println(Array(1, 2, 3) == Array(1, 2, 3))") shouldBe "false"

  test("Array equals itself by reference"):
    captured("val a = Array(1); println(a == a)") shouldBe "true"

  test("Array.map returns a (mutable) Array; mkString reads it"):
    captured("val a = Array(1, 2, 3).map(x => x * 2); a(0) = 100; println(a.mkString(\",\"))") shouldBe "100,4,6"

  test("Array.fill / Array.tabulate"):
    captured("println(Array.fill(3)(0).mkString(\",\"))") shouldBe "0,0,0"
    captured("println(Array.tabulate(4)(i => i * i).mkString(\",\"))") shouldBe "0,1,4,9"

  test("Array.toList / sameElements"):
    captured("println(Array(1, 2, 3).toList)") shouldBe "List(1, 2, 3)"
    captured("println(Array(1, 2, 3).sameElements(Array(1, 2, 3)))") shouldBe "true"

  // ── LazyList: real laziness, infinite streams ─────────────────────────────

  test("LazyList.from infinite source, only the demanded prefix forced"):
    captured("println(LazyList.from(1).map(x => x * 2).take(3).toList)") shouldBe "List(2, 4, 6)"

  test("LazyList.toString shows <not computed> until forced (JVM parity)"):
    captured("println(LazyList(1, 2, 3))") shouldBe "LazyList(<not computed>)"

  test("LazyList.iterate is lazy/infinite"):
    captured("println(LazyList.iterate(1)(x => x * 2).take(5).toList)") shouldBe "List(1, 2, 4, 8, 16)"

  test("LazyList.continually is lazy/infinite"):
    captured("println(LazyList.continually(7).take(3).toList)") shouldBe "List(7, 7, 7)"

  test("LazyList filter + take on an infinite source"):
    captured("println(LazyList.from(1).filter(x => x % 2 == 0).take(3).toList)") shouldBe "List(2, 4, 6)"

  test("self-recursive #:: stream definition yields an infinite LazyList"):
    captured("""
      def from(n: Int): LazyList[Int] = n #:: from(n + 1)
      println(from(1).take(4).toList)
    """) shouldBe "List(1, 2, 3, 4)"

  test("LazyList.range / toVector"):
    captured("println(LazyList.range(0, 5).toVector)") shouldBe "Vector(0, 1, 2, 3, 4)"
