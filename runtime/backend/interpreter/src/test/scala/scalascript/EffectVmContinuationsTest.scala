package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** effect-vm-continuations (specs/effect-vm-continuations.md): a one-shot tail-resume handler
 *  resolves its effect op at the perform site (resolver), and the JIT compiles the now-pure
 *  effectful loop body via the `resolveEffectLong` bridge (effectOneShot ~18 → ~1.7 ms).
 *  These pin correctness: the JIT path vs the general (deep / multi-shot) paths give identical
 *  results, and the SAME effect deep-handled in a loop still bails the bridge safely. */
class EffectVmContinuationsTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# T\n\n```scalascript\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  private val Bump =
    """effect Bump:
      |  def tick(): Int
      |def loop(n: Int): Int ! Bump =
      |  var acc = 0
      |  var i = 0
      |  while i < n do
      |    acc = acc + Bump.tick()
      |    i = i + 1
      |  acc
      |""".stripMargin

  test("JIT bridge path: one-shot tail-resume accumulation (long loop)"):
    run(Bump + """println(handle(loop(5000)) { case Bump.tick(resume) => resume(1) })""") shouldBe "5000"

  test("JIT bridge path: resume value is a constant other than 1"):
    run(Bump + """println(handle(loop(1000)) { case Bump.tick(resume) => resume(3) })""") shouldBe "3000"

  test("SAME effect, DEEP handler in a loop: bridge bails, deep accumulation correct"):
    run("""
      effect Log:
        def emit(): Int
      def loop(n: Int): Int ! Log =
        var acc = 0
        var i = 0
        while i < n do
          acc = acc + Log.emit()
          i = i + 1
        acc
      val xs: List[Int] = handle(loop(3)) {
        case Log.emit(resume) => 7 :: resume(1)
        case Return(_) => List()
      }
      println(xs)
    """) shouldBe "List(7, 7, 7)"  // deep handler: each emit prepends 7; resume(1) feeds the loop

  test("op with an arg, one-shot tail-resume (resolver binds the op-arg)"):
    run("""
      effect Reader:
        def ask(k: Int): Int
      def loop(n: Int): Int ! Reader =
        var acc = 0
        var i = 0
        while i < n do
          acc = acc + Reader.ask(i)
          i = i + 1
        acc
      println(handle(loop(5)) { case Reader.ask(k, resume) => resume(k * 10) })
    """) shouldBe "100"  // sum_{i=0..4} (i*10) = 100

  test("two-arg effect op + arithmetic resume expr (P2b: resolveEffectLong2)"):
    run("""
      effect Combine:
        def mix(a: Int, b: Int): Int
      def loop(n: Int): Int ! Combine =
        var acc = 0
        var i = 0
        while i < n do
          acc = acc + Combine.mix(i, i + 1)
          i = i + 1
        acc
      println(handle(loop(5)) { case Combine.mix(a, b, resume) => resume(a * b + 1) })
    """) shouldBe "45"  // sum_{i=0..4} (i*(i+1) + 1) = 1+3+7+13+21 = 45

  test("multi-shot effect in a loop is unaffected (no resolver)"):
    run("""
      multi effect NonDet:
        def choose(opts: List[Int]): Int
      def prog(): Int ! NonDet =
        val a = NonDet.choose(List(1, 2))
        val b = NonDet.choose(List(10, 20))
        a + b
      val all = handle(prog()) {
        case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o))
      }
      println(all.length)
    """) shouldBe "4"

  // effect-vm-cont-p3: the `List(1,2,3,4)` arg is now memoised by `pureConstCache` (const
  // immutable collection literal). Pin that the SHARED cached list does not corrupt the
  // multi-shot enumeration — 4 nested `choose` over a 4-elem list must still yield 4^4 = 256
  // independent paths, and the sum is path-dependent (not folded).
  test("p3: multi-shot over a cached const list literal yields all 256 paths"):
    run("""
      multi effect NonDet:
        def choose(options: List[Int]): Int
      def program(): Int ! NonDet =
        val a = NonDet.choose(List(1, 2, 3, 4))
        val b = NonDet.choose(List(1, 2, 3, 4))
        val c = NonDet.choose(List(1, 2, 3, 4))
        val d = NonDet.choose(List(1, 2, 3, 4))
        a + b + c + d
      val all = handle(program()) {
        case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      }
      println(all.length.toString + "/" + all.sum.toString)
    """) shouldBe "256/2560"  // 256 paths; Σ sums = 256·(mean 2.5·4) = 256·10 = 2560

  // effect-vm-cont-p3b: the `effectMultiShotDeep` stress shape — 5 levels × 5 options with
  // INTERLEAVED per-step scoring (`val sa = a*a; val b = choose(..); val sb = b*b+sa; …`). Pins
  // that the unapply-allocation cuts in the continuation re-eval path (Defn.Val / ApplyInfix
  // type-tests in BlockRuntime.step / EvalRuntime.fastPrimitiveValue) preserve multi-shot
  // semantics across all 3125 paths.
  test("p3b: deep interleaved multi-shot (5×5) yields all 3125 paths, exact score sum"):
    run("""
      multi effect NonDet:
        def choose(options: List[Int]): Int
      def search(): Int ! NonDet =
        val a = NonDet.choose(List(1, 2, 3, 4, 5))
        val sa = a * a
        val b = NonDet.choose(List(1, 2, 3, 4, 5))
        val sb = b * b + sa
        val c = NonDet.choose(List(1, 2, 3, 4, 5))
        val sc = c * c + sb
        val d = NonDet.choose(List(1, 2, 3, 4, 5))
        val sd = d * d + sc
        val e = NonDet.choose(List(1, 2, 3, 4, 5))
        e * e + sd
      val all = handle(search()) {
        case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
      }
      println(all.length.toString + "/" + all.sum.toString)
    """) shouldBe "3125/171875"  // 5^5 paths; Σ(a²+…+e²) = 5·(55·5⁴) = 5·34375 = 171875

  // ── effect-vm-cont-p2c: compiled pure-Int-arith resume expressions ──
  // The resolver compiles `+`/`-`/`*`/unary `±` resume exprs to a Long closure instead of
  // tree-walking `interp.eval` each perform. These pin that the compiled path is numerically
  // identical to the old interp.eval path across nested/unary/subtraction shapes, and that
  // a non-compilable shape (division) falls back to interp.eval and stays correct.

  private val Reader =
    """effect Reader:
      |  def ask(k: Int): Int
      |def loop(n: Int): Int ! Reader =
      |  var acc = 0
      |  var i = 0
      |  while i < n do
      |    acc = acc + Reader.ask(i)
      |    i = i + 1
      |  acc
      |""".stripMargin

  test("p2c: subtraction + nested arith resume expr (compiled path)"):
    run(Reader + """println(handle(loop(5)) { case Reader.ask(k, resume) => resume((k + 1) * 2 - 3) })""")
      .shouldBe("15")  // sum_{i=0..4} ((i+1)*2 - 3) = -1+1+3+5+7 = 15

  test("p2c: long compiled-arith loop computes the exact sum (not folded)"):
    // loop(5000) with resume(k*2) ⇒ 2·Σ(i=0..4999) i = 4999·5000 = 24_995_000. Pins that the
    // compiled-resolver path runs every iteration with correct per-iter arithmetic at scale.
    run(Reader + """println(handle(loop(5000)) { case Reader.ask(k, resume) => resume(k * 2) })""")
      .shouldBe("24995000")

  test("p2c: unary-minus resume expr (compiled path)"):
    run(Reader + """println(handle(loop(4)) { case Reader.ask(k, resume) => resume(-k + 10) })""")
      .shouldBe("34")  // sum_{i=0..3} (10 - i) = 10+9+8+7 = 34

  test("p2c: division resume expr falls back to interp.eval (still correct)"):
    run(Reader + """println(handle(loop(6)) { case Reader.ask(k, resume) => resume(k / 2) })""")
      .shouldBe("6")   // sum_{i=0..5} (i/2) = 0+0+1+1+2+2 = 6 (integer division; slow path)

  test("p2c: zero-arg constant resume compiles (effectOneShot shape)"):
    run("""
      effect Bump:
        def tick(): Int
      def loop(n: Int): Int ! Bump =
        var acc = 0
        var i = 0
        while i < n do
          acc = acc + Bump.tick()
          i = i + 1
        acc
      println(handle(loop(1000)) { case Bump.tick(resume) => resume(2 * 3 + 1) })
    """) shouldBe "7000"  // 1000 * 7
