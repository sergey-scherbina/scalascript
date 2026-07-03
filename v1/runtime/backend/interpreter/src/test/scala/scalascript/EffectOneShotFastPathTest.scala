package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** effect-oneshot-perf: the one-shot tail-resume fast path (a bare
 *  `case Eff.op(..) => resume(simpleArgs)` arm skips the per-perform placeholder +
 *  resume-NativeFnV allocation; −39% alloc/op on `effectOneShot`). These pin that it
 *  is semantically identical to the general placeholder path across the shapes that
 *  do / don't take the fast path. */
class EffectOneShotFastPathTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(s"# T\n\n```scalascript\n$code\n```\n"))
    ps.flush(); buf.toString.trim

  test("bare tail-resume (fast path): accumulate over a perform loop"):
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
      println(handle(loop(100)) { case Bump.tick(resume) => resume(1) })
    """) shouldBe "100"

  test("tail-resume with op-arg binding (fast path): resume(x) uses the bound arg"):
    run("""
      effect Reader:
        def ask(): Int
      def prog(): Int ! Reader =
        val a = Reader.ask()
        val b = Reader.ask()
        a + b
      println(handle(prog()) { case Reader.ask(resume) => resume(21) })
    """) shouldBe "42"

  test("non-tail resume (general path): msg :: resume(()) deep accumulation"):
    run("""
      effect Log:
        def log(msg: String): Unit
      def prog(): Unit ! Log =
        Log.log("a")
        Log.log("b")
      val r: List[String] = handle(prog()) {
        case Log.log(msg, resume) => msg :: resume(())
        case Return(_) => List()
      }
      println(r)
    """) shouldBe "List(a, b)"

  test("guarded arm (general path): guard still selects correctly"):
    run("""
      effect Pick:
        def choose(n: Int): Int
      def prog(): Int ! Pick =
        Pick.choose(5)
      val r = handle(prog()) {
        case Pick.choose(n, resume) if n > 3 => resume(n * 10)
        case Pick.choose(n, resume)          => resume(n)
      }
      println(r)
    """) shouldBe "50"

  test("resume(()) on a Unit effect (fast path): loop of side-effecting performs"):
    run("""
      effect Emit:
        def put(x: Int): Unit
      def prog(): Int ! Emit =
        var i = 0
        while i < 5 do
          Emit.put(i)
          i = i + 1
        i
      println(handle(prog()) { case Emit.put(x, resume) => resume(()) })
    """) shouldBe "5"
