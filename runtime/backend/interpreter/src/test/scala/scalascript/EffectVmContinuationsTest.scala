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
