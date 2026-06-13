package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression tests for bugs found in busi (scalascript-issues.md).
 *  Uses .ssc-style indentation (no extra leading spaces) to match real file parsing. */
class BugReproTest extends AnyFunSuite with Matchers:

  private def captured(ssc: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scalascript\n$ssc\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  // ── while-loop: var i += 1 with tuple-list accumulator ───────────────────

  test("while-loop var i advances with simple list") {
    captured(
"""var i = 0
var result = List()
while i < 3 do
  result = result :+ i
  i += 1
println(result.length)
println(i)"""
    ) shouldBe "3\n3"
  }

  test("while-loop var i advances with tuple accumulation") {
    captured(
"""var i = 0
var result = List()
while i < 3 do
  result = result :+ (i, "r" + i)
  i += 1
println(result.length)
println(i)"""
    ) shouldBe "3\n3"
  }

  test("while-loop result contains correct values") {
    captured(
"""var i = 0
var result = List()
while i < 3 do
  result = result :+ (i, "r" + i)
  i += 1
println(result.map(p => p._1.toString + p._2).mkString(","))"""
    ) shouldBe "0r0,1r1,2r2"
  }

  test("while-loop accumulates into a typed empty tuple list") {
    captured(
"""var i = 0
var result: List[(Int, String)] = List[(Int, String)]()
while i < 3 do
  result = result :+ (i, "r" + i)
  i += 1
println(result.length)
println(i)"""
    ) shouldBe "3\n3"
  }

  // Same shape, but the loop is hot enough to tier up to the while-JIT — guards
  // against the JIT treating the typed-list accumulator var as a dead/counter slot.
  test("hot function with typed-list while loop keeps appends (while-JIT path)") {
    captured(
"""def build(n: Int): Int =
  var i = 0
  var result: List[(Int, Int)] = List[(Int, Int)]()
  while i < n do
    result = result :+ (i, i * 2)
    i += 1
  result.length
var total = 0
var k = 0
while k < 200000 do
  total = build(3)
  k += 1
println(total)"""
    ) shouldBe "3"
  }

  // jit-walklocalslotctx-so — a hot while loop whose accumulator adds a RECURSIVE
  // call (`s = s + fib(20)`). The while-JIT's `walkLocalSlotCtx` → `tryCompile(fib)`
  // path used to re-enter `tryCompile` for fib's own self-recursive body and overflow
  // the stack (caught + bailed, but expensively). The in-progress sentinel breaks the
  // re-entry; the program must run correctly (and fast) either way.
  test("hot while loop accumulating a recursive call does not overflow (jit-walklocalslotctx-so)") {
    captured(
"""def fib(n: Int): Int =
  if n <= 1 then n
  else fib(n - 1) + fib(n - 2)
def run(n: Int): Int =
  var s = 0
  var i = 0
  while i < n do
    s = s + fib(20)
    i = i + 1
  s
println(run(200000))"""
    ) shouldBe "1353000000"
  }

  // ── multi-line function body: last expression must be returned ────────────

  test("multi-line function: binary expression as last statement") {
    captured(
"""def buildJson(x: String): String =
  val a = "part1"
  val b = "part2"
  "{" + a + "," + b + "}"
println(buildJson("x"))"""
    ) shouldBe "{part1,part2}"
  }

  test("multi-line function: call expression as last statement") {
    captured(
"""def wrap(items: List[String]): String =
  val sep = ","
  items.mkString(sep)
println(wrap(List("a", "b", "c")))"""
    ) shouldBe "a,b,c"
  }

  test("multi-line function: last statement after a flatMap/map chain") {
    captured(
"""def report(xs: List[Int]): String =
  val doubled = xs.map(x => x * 2)
  doubled.mkString("+")
println(report(List(1, 2, 3)))"""
    ) shouldBe "2+4+6"
  }

  test("tuple construction in while loop body") {
    captured(
"""var i = 0
var result = List()
while i < 2 do
  val t = (i, "r" + i)
  println(t._1)
  println(t._2)
  i += 1"""
    ) shouldBe "0\nr0\n1\nr1"
  }

  test("append tuple via :+ in while loop") {
    captured(
"""var result = List()
val t = (42, "hello")
result = result :+ t
println(result.length)
println(result(0)._1)"""
    ) shouldBe "1\n42"
  }

  test("InstanceV(Map, ...) is callable via direct apply") {
    captured(
"""val m = Map("a" -> 1)
println(m("a"))""") shouldBe "1"
  }

  // ── asInstanceOf is a no-op for all Value subtypes ────────────────────────

  test("asInstanceOf no-op on Map") {
    captured(
"""val m = Map("a" -> 1)
val m2 = m.asInstanceOf[Map[String, Int]]
println(m2("a"))"""
    ) shouldBe "1"
  }

  test("asInstanceOf no-op on List") {
    captured(
"""val xs = List(1, 2, 3)
val ys = xs.asInstanceOf[List[Int]]
println(ys.length)"""
    ) shouldBe "3"
  }

  test("asInstanceOf no-op on Int") {
    captured(
"""val n: Any = 42
println(n.asInstanceOf[Int])"""
    ) shouldBe "42"
  }

  // ── var declared inside a function, mutated inside a while-loop closure ───

  test("var total accumulated via foreach closure in while loop") {
    captured(
"""sealed trait Shape
case class Circle(r: Double) extends Shape
case class Rect(w: Double, h: Double) extends Shape
def area(s: Shape): Double = s match
  case Circle(r) => r * r
  case Rect(w, h) => w * h
val shapes = List(Circle(2.0), Rect(3.0, 4.0))
def workload(): Double =
  var total = 0.0
  var i = 0
  while i < 10 do
    shapes.foreach(s => { total = total + area(s) })
    i = i + 1
  total
val result = workload()
println(result)""") shouldBe "160"
    // 10 * (2*2 + 3*4) = 10 * (4 + 12) = 10 * 16 = 160.0
  }

  test("var counter returned after while loop") {
    captured(
"""def countUp(n: Int): Int =
  var i = 0
  while i < n do
    i = i + 1
  i
println(countUp(7))""") shouldBe "7"
  }

  // ── busi-p0-try-catch-handler ────────────────────────────────────────────

  test("try/catch with handler function (no case keyword)") {
    captured(
"""val r = try
  throw RuntimeException("boom")
  "ok"
catch e => "caught: " + e.message
println(r)""") shouldBe "caught: boom"
  }

  test("try/catch handler ignores exception with wildcard") {
    captured(
"""val r = try throw RuntimeException("x") catch _ => "handled"
println(r)""") shouldBe "handled"
  }

  // ── busi-p1-map-concat-returns-tuplev ────────────────────────────────────

  test("Map ++ Map merges both maps") {
    captured(
"""val a = Map("x" -> "1")
val b = Map("z" -> "3")
val c = a ++ b
println(c.get("x").getOrElse("missing"))
println(c.get("z").getOrElse("missing"))""") shouldBe "1\n3"
  }

  test("Map ++ Map via infix operator") {
    captured(
"""val m1 = Map("a" -> 1)
val m2 = Map("b" -> 2)
val m3 = m1 ++ m2
println(m3.get("a").getOrElse(0))
println(m3.get("b").getOrElse(0))""") shouldBe "1\n2"
  }

  // ── busi-p1-map-getorelse-null-semantics ─────────────────────────────────

  test("Map.getOrElse returns default when value is null") {
    captured(
"""val m = Map("k" -> null)
val r = m.getOrElse("k", "default")
println(r)""") shouldBe "default"
  }

  // ── busi-p1-phase90-rule-bool-coercion ───────────────────────────────────

  test("unary ! on Int 0 returns true") {
    captured(
"""val n: Int = 0
println(!n)""") shouldBe "true"
  }

  test("unary ! on Int 1 returns false") {
    captured(
"""val n: Int = 1
println(!n)""") shouldBe "false"
  }

  test("unary ! on Int nonzero returns false") {
    captured(
"""val n: Int = 42
println(!n)""") shouldBe "false"
  }

  // ── busi-p1-arrow-vs-plus-precedence ─────────────────────────────────────

  test("Map key -> string + value: string concat absorbed into tuple") {
    captured(
"""val value = "World"
val m = Map("k" -> "Hello " + value)
println(m.get("k").getOrElse("missing"))""") shouldBe "Hello World"
  }

  test("Map multi-entry with -> string + val") {
    captured(
"""val prefix = "Org: "
val name = "Acme"
val m = Map("name" -> prefix + name, "code" -> "A")
println(m.get("name").getOrElse("missing"))""") shouldBe "Org: Acme"
  }

  // ── busi-string-indexOf ───────────────────────────────────────────────────

  test("String.indexOf(str) returns position") {
    captured("""println("hello world".indexOf("world"))""") shouldBe "6"
  }

  test("String.indexOf(str) returns -1 when not found") {
    captured("""println("hello".indexOf("xyz"))""") shouldBe "-1"
  }

  test("String.indexOf(str, from) skips before fromIndex") {
    captured("""println("abcabc".indexOf("a", 1))""") shouldBe "3"
  }

  test("String.indexOf(char code as Int) finds char") {
    captured(
"""val s = "hello"
val idx = s.indexOf(101)
println(idx)""") shouldBe "1"
  }

  test("String.lastIndexOf(str) returns last position") {
    captured("""println("abcabc".lastIndexOf("a"))""") shouldBe "3"
  }

  test("String.lastIndexOf(str, from) stops at fromIndex") {
    captured("""println("abcabc".lastIndexOf("a", 2))""") shouldBe "0"
  }

  // ── busi-string-split-regex ───────────────────────────────────────────────

  test("String.split with regex literal dot (\\\\.)") {
    captured(
"""val parts = "a.b.c".split("\\.")
println(parts.length)
println(parts(0))
println(parts(2))""") shouldBe "3\na\nc"
  }

  test("String.split with regex whitespace (\\\\s+)") {
    captured(
"""val parts = "hello   world".split("\\s+")
println(parts.length)
println(parts(1))""") shouldBe "2\nworld"
  }

  test("String.split(sep, limit) limits result count") {
    captured(
"""val parts = "a:b:c:d".split(":", 2)
println(parts.length)
println(parts(1))""") shouldBe "2\nb:c:d"
  }

  test("String.split with pipe char (no escape needed for literal)") {
    captured(
"""val parts = "a|b|c".split("\\|")
println(parts.length)""") shouldBe "3"
  }

  // ── foldLeft into Map[String, wide case class] with per-key reconstruction ──
  // busi `applyRetirement` shape. The "Instance is not callable" failure came
  // from wide (10+ field) case classes whose pre-fieldsArr HashMap fields
  // representation was mishandled; the Direction B flag-flip unified all field
  // counts onto fieldsArr, so keyed-access / values.toList.sortBy now work.

  test("foldLeft updating Map of 11-field case class then values.toList.sortBy") {
    captured(
"""case class Acc(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int, k: Int)
val items = List("x", "x", "x", "y")
val m = items.foldLeft(Map[String, Acc]())((acc, key) =>
  val cur = acc.getOrElse(key, Acc(0,0,0,0,0,0,0,0,0,0,0))
  acc + (key -> Acc(cur.a + 1, cur.b, cur.c, cur.d, cur.e, cur.f, cur.g, cur.h, cur.i, cur.j, cur.k)))
println(m.values.toList.sortBy(p => p.a).map(p => p.a).mkString(","))""") shouldBe "1,3"
  }

  test("foldLeft with match branch reconstructing wide case class then keyed access") {
    captured(
"""case class P(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int)
val events = List(("x", 1), ("y", 2), ("x", 3))
val m = events.foldLeft(Map[String, P]())((acc, ev) =>
  ev match
    case (key, n) =>
      val cur = acc.getOrElse(key, P(0,0,0,0,0,0,0,0,0,0))
      acc + (key -> P(cur.a + n, cur.b, cur.c, cur.d, cur.e, cur.f, cur.g, cur.h, cur.i, cur.j)))
println(m("x").a)
println(m.values.toList.sortBy(p => p.a).map(p => p.a).mkString(","))""") shouldBe "4\n2,4"
  }

  // ── enum → intermediate-trait → trait hierarchy resolution (busi seq-120/121) ──

  test("type-test by an intermediate sealed-trait supertype (busi seq-120)") {
    captured(
"""sealed trait Event
sealed trait TaxEvent extends Event
enum PolishTaxEvent extends TaxEvent:
  case PolishVatConfigured(code: String)

val p: Event = PolishVatConfigured("23")
val r = p match
  case _: TaxEvent => "yes"
  case _ => "no"
println(r)""") shouldBe "yes"
  }

  test("type-test by the top trait and the enum, through the chain (busi seq-120)") {
    captured(
"""sealed trait Event
sealed trait TaxEvent extends Event
enum PolishTaxEvent extends TaxEvent:
  case PolishVatConfigured(code: String)

val p: Event = PolishVatConfigured("23")
println(p match { case _: Event => "e"; case _ => "x" })
println(p match { case _: PolishTaxEvent => "pte"; case _ => "x" })
println(p match { case _: TaxEvent => "te"; case _ => "x" })""") shouldBe "e\npte\nte"
  }

  test("concrete method on a sealed trait dispatches on enum-case instances (busi seq-121)") {
    captured(
"""sealed trait Event:
  def kind: String = this match
    case AccountCreated(c) => "ledger:" + c
    case PartyCreated(i)   => "party:" + i
enum LedgerEvent extends Event:
  case AccountCreated(code: String)
enum PartyEvent extends Event:
  case PartyCreated(id: String)

val e: Event = AccountCreated("1000")
println(e.kind)
val p: Event = PartyCreated("p7")
println(p.kind)""") shouldBe "ledger:1000\nparty:p7"
  }

  test("`this` resolves to the receiver in a case-class method body") {
    captured(
"""case class Foo(x: Int):
  def describe: String = this match
    case Foo(n) => "foo:" + n
println(Foo(5).describe)""") shouldBe "foo:5"
  }

  test("inherited trait method with an argument dispatches on a subtype instance") {
    captured(
"""sealed trait Shape:
  def scaled(by: Int): Int = this match
    case Square(s) => s * by
enum Shapes extends Shape:
  case Square(side: Int)
val sh: Shape = Square(3)
println(sh.scaled(4))""") shouldBe "12"
  }

  test("an instance field shadows an inherited method of the same name") {
    captured(
"""sealed trait Named:
  def label: String = "trait-label"
case class Tagged(label: String) extends Named
println(Tagged("field-label").label)""") shouldBe "field-label"
  }

  // ── JIT-compiled supertype type-test (follow-up to seq-124) ──────────────────
  // These force the matcher FUNCTION hot (50k calls) so it JIT-compiles, then
  // assert correctness — the JIT now lowers `case _: Supertype` to a runtime
  // isSubtype parent-chain check (if-chain) instead of an exact-tag switch.

  test("hot JIT'd function: supertype type-test (statement form) stays correct") {
    captured(
"""sealed trait E
sealed trait Tax extends E
enum PE extends Tax:
  case V(c: String)
def isTax(e: E): Boolean = e match
  case _: Tax => true
  case _      => false
var n = 0
var hits = 0
while n < 50000 do
  if isTax(V("x")) then hits = hits + 1
  n = n + 1
println(hits)""") shouldBe "50000"
  }

  test("hot JIT'd function: supertype type-test in a larger expression stays correct") {
    captured(
"""sealed trait E
sealed trait Tax extends E
enum PE extends Tax:
  case V(c: String)
def score(e: E): Int = 1 + (e match
  case _: Tax => 10
  case _      => 20)
var n = 0
var total = 0
while n < 50000 do
  total = total + score(V("x"))
  n = n + 1
println(total)""") shouldBe "550000"
  }

  test("hot JIT'd function: leaf + supertype arms ordered correctly") {
    captured(
"""sealed trait E
sealed trait Tax extends E
enum PE extends Tax:
  case Vat(c: String)
  case Other(c: String)
def label(e: E): String = e match
  case Vat(c) => "vat:" + c
  case _: Tax => "tax"
  case _      => "?"
var n = 0
var s = ""
while n < 50000 do
  s = label(Vat("23"))
  n = n + 1
println(s + "|" + label(Other("x")))""") shouldBe "vat:23|tax"
  }

  // A `while` accumulator that calls a recursive fn triggered a StackOverflowError
  // in the JIT while-loop codegen (walkLocalSlotCtx) on the *real CLI* (fat jar /
  // bin/ssc) — and crashed the program instead of bailing. The fix wraps the JIT
  // compile entries so any codegen crash bails to tree-walk. NOTE: in this sbt
  // harness the javac JIT can't find its runtime classes and bails anyway (so this
  // asserts the shape's CORRECTNESS; the crash-safety itself is verified on the fat
  // jar). If the JIT classpath is ever fixed for sbt, this also guards the crash.
  test("while accumulator over a recursive call computes correctly (JIT codegen SO shape)") {
    captured(
"""def fib(n: Int): Int = if n < 2 then n else fib(n - 1) + fib(n - 2)
var i = 0
var s = 0
while i < 35 do
  s = s + fib(20)
  i = i + 1
println(s)""") shouldBe "236775"
  }

  // `.toString` is universal in Scala but the interpreter had no dispatch for a
  // ListV (and other composite Values) → `No method 'toString' on ListV`. Now it
  // falls back to the same render as println / string interpolation (Value.show).
  test("toString on a list renders like println (interp-toString-on-collection)") {
    captured("""println(List(1, 2, 3).toString)""") shouldBe "List(1, 2, 3)"
  }

  test("toString matches the canonical render for composite values") {
    // `.toString` must equal what `s"$value"` / println produces, for every
    // composite Value type that previously errored.
    captured(
"""val xs = List(1, 2, 3)
val m  = Map("a" -> 1)
val t  = (1, "x")
val o: Option[Int] = Some(5)
case class P(name: String, age: Int)
val p = P("Ann", 30)
println(xs.toString == s"$xs")
println(m.toString  == s"$m")
println(t.toString  == s"$t")
println(o.toString  == s"$o")
println(p.toString  == s"$p")""") shouldBe "true\ntrue\ntrue\ntrue\ntrue"
  }

  // A bare function-valued member (`println`, rewritten to `Console.println`) passed as a
  // value was auto-invoked → `()` → `Not callable`. Now a bare `a.b` member reference
  // returns the function (eta); only an actual call `a.b(...)` invokes it.
  test("bare println reference is not auto-invoked — foreach(println) (interp-bare-fn-ref)") {
    captured("""List("a", "b", "c").foreach(println)""") shouldBe "a\nb\nc"
  }

  test("println bound to a val is a callable function") {
    captured(
"""val p = println
p("hi")
p("bye")""") shouldBe "hi\nbye"
  }

  test("explicit println() / println(x) still invoke (eta change is reference-only)") {
    // println() prints a blank line; the empty-paren CALL must still invoke.
    captured(
"""println("x")
println()
println("y")""") shouldBe "x\n\ny"
  }

  test("0-arg native getter called with parens still works (nanoTime)") {
    captured("""println(if nanoTime() > 0L then "ok" else "bad")""") shouldBe "ok"
  }
