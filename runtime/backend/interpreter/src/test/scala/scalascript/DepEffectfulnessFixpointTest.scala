package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** Unit tests for Strategy D Step 2 — `analyzeDepEffectfulness`.
 *
 *  Verifies the cross-dep fixpoint correctly propagates effectfulness:
 *  defs that call primitives directly are marked; defs that call already-
 *  marked defs are marked transitively; pure defs are not marked.
 *
 *  See docs/dep-cps-rewrite.md §6 Step 2 for acceptance criteria. */
class DepEffectfulnessFixpointTest extends AnyFunSuite with Matchers:

  /** Build a JvmGen, seed `depDefs` from the source by parsing it
   *  through the real Parser (so the same scalameta tree shapes flow
   *  through that `inlineImport` would produce), run the fixpoint,
   *  return the resulting effectful set. */
  private def analyse(src: String): Set[String] =
    val gen = new JvmGen()
    val module = Parser.parse(s"# Test\n\n```scalascript\n$src\n```\n")
    gen.seedDepDefsForTest(module)
    gen.analyzeDepEffectfulness()
    gen.globalEffectfulDepsForTest.toSet

  test("seed: def calling Random.uuid is effectful") {
    val effectful = analyse("""
      def makeId(): String = Random.uuid().asInstanceOf[String]
    """)
    effectful shouldBe Set("makeId")
  }

  test("seed: def calling self() is effectful") {
    val effectful = analyse("""
      def whoami(): Any = self()
    """)
    effectful shouldBe Set("whoami")
  }

  test("seed: pure def is not effectful") {
    val effectful = analyse("""
      def add(a: Int, b: Int): Int = a + b
    """)
    effectful shouldBe Set.empty
  }

  test("transitive: pingPair -> ping (1 hop)") {
    val effectful = analyse("""
      def ping(pid: Any): String =
        pid ! "ping"
        receive { case "pong" => "ok" }

      def pingPair(p1: Any, p2: Any): String =
        val first  = ping(p1)
        val second = ping(p2)
        first + "/" + second
    """)
    effectful shouldBe Set("ping", "pingPair")
  }

  test("transitive: 3-hop chain f -> g -> h -> primitive") {
    val effectful = analyse("""
      def h(): Any = self()
      def g(): Any = h()
      def f(): Any = g()
    """)
    effectful shouldBe Set("h", "g", "f")
  }

  test("siblings: marked + unmarked coexist") {
    val effectful = analyse("""
      def needsActor(pid: Any): Any = pid ! "msg"
      def pureHelper(x: Int): Int = x + 1
    """)
    effectful shouldBe Set("needsActor")
  }

  test("conditional effect: if-arm contains primitive — marked") {
    val effectful = analyse("""
      def maybeAsk(pid: Any, useCache: Boolean): String =
        if useCache then "cached"
        else { pid ! "ask"; receive { case s => s.toString } }
    """)
    effectful shouldBe Set("maybeAsk")
  }

  test("nested def: outer pure, inner effectful — both marked (over-approximation)") {
    val effectful = analyse("""
      def outer(): Int =
        def inner(): Any = self()
        42
    """)
    // The predicate walks the entire tree including nested def bodies.
    // `outer` is marked even though it only returns 42 — safe false
    // positive, Step 3 CPS-emits `42` to `42` via `_bind`'s value branch.
    // See `containsEffectPrimitive` docstring for the rationale.
    effectful shouldBe Set("inner", "outer")
  }

  test("nested def: outer calls inner — both marked transitively") {
    val effectful = analyse("""
      def outer(): Any =
        def inner(): Any = self()
        inner()
    """)
    effectful shouldBe Set("inner", "outer")
  }

  test("mutual recursion: both reach a primitive — both marked") {
    val effectful = analyse("""
      def a(n: Int): Any = if n <= 0 then self() else b(n - 1)
      def b(n: Int): Any = a(n - 1)
    """)
    effectful shouldBe Set("a", "b")
  }

  test("mutual recursion: neither reaches a primitive — neither marked") {
    val effectful = analyse("""
      def a(n: Int): Int = if n <= 0 then 0 else b(n - 1)
      def b(n: Int): Int = a(n - 1)
    """)
    effectful shouldBe Set.empty
  }

  test("call to non-dep external function — not effectful") {
    val effectful = analyse("""
      def f(): Int = SomeLibrary.compute(42)
    """)
    effectful shouldBe Set.empty
  }

  test("def inside object — collected and analysed (simple-name keying)") {
    val effectful = analyse("""
      object WorkerProtocol:
        def handleMessages(): Any =
          receive { case x => x }
    """)
    // Walks into object body and collects `handleMessages`.
    effectful shouldBe Set("handleMessages")
  }

  test("qualified call to effectful dep method marks wrapper") {
    val effectful = analyse("""
      object Wire:
        def runRaw(): Any = self()

      object Typed:
        def run(): Any = Wire.runRaw()
    """)
    effectful shouldBe Set("runRaw", "run")
  }

  test("generic qualified call to effectful dep method marks wrapper") {
    val effectful = analyse("""
      object Wire:
        def runRaw[A](): Any = self()

      object Typed:
        def run[A](): Any = Wire.runRaw[A]()
    """)
    effectful shouldBe Set("runRaw", "run")
  }

  test("fixpoint converges in one extra iteration for typical chains") {
    // 5-deep chain: ensure we don't loop forever on healthy inputs.
    val effectful = analyse("""
      def a(): Any = self()
      def b(): Any = a()
      def c(): Any = b()
      def d(): Any = c()
      def e(): Any = d()
    """)
    effectful shouldBe Set("a", "b", "c", "d", "e")
  }
