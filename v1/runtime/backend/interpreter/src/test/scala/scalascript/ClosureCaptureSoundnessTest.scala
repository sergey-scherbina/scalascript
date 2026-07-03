package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Closure capture must distinguish a genuine frame-local from a top-level global by
 *  **scope/identity**, not by **value equality**.
 *
 *  The free-var-limited capture (effect-vm-cont slice 3f) dropped a body-referenced name
 *  whose env value structurally *equalled* the same-named global, treating it as a global
 *  to be re-read live at call time. But `Value` has structural equality, so a genuine
 *  frame-local that merely coincided in value with a same-named global (e.g. a per-request
 *  value equal to a global default) was misclassified, dropped, and — via the empty-closure
 *  cache reused across calls — re-read live in another context, leaking that context's value
 *  (a cross-tenant state leak observed in the busi server: phase50g/phase87b). The fix gates
 *  on reference identity (`ne`): a distinct binding object is a real capture; only the literal
 *  global object is re-read live. */
class ClosureCaptureSoundnessTest extends AnyFunSuite with Matchers:

  private def run(src: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(os.temp.dir(prefix = "ssc-capture-"))).run(Parser.parse(src))
    ps.flush(); buf.toString.trim

  test("a frame-local equal-in-value to a same-named global is captured (not re-read live)"):
    // local `k` == global `k` by value but is a distinct object; the closure must keep the
    // captured local even after the global is reassigned.
    run(
      """case class Box(label: String)
        |var k = Box("G")
        |def make(): () => String =
        |  val k = Box("G")
        |  () => k.label
        |val f = make()
        |k = Box("H")
        |println(f())
        |""".stripMargin) shouldBe "G"

  test("a true global var (no local shadow) is still re-read live at call time"):
    // the optimization this guards must not over-capture genuine globals.
    run(
      """var g = 1
        |def mk(): () => Int = () => g
        |val f = mk()
        |g = 2
        |println(f())
        |""".stripMargin) shouldBe "2"

  test("a destructuring `val (a, b) = …` must not mark a pre-existing var as a val"):
    // interp-stream-runforeach-var-capture: `matchPat` for a tuple pattern returns the
    // *full* threaded env, so binding all of it (and adding every name to `valNames`)
    // wrongly marked the earlier `var n` as a `val`. A later var-mutating lambda then
    // snapshot-captured `n` instead of re-reading it live, so every call but the last
    // was lost — `n` ended at 1, not 3.
    run(
      """def thrice(g: Int => Unit): Unit = { g(1); g(1); g(1) }
        |var n = 0
        |val (a, b) = (7, 8)
        |thrice(x => { n = n + 1 })
        |println(n)
        |""".stripMargin) shouldBe "3"

  test("destructured names are still captured correctly (a real val, not re-read-live)"):
    // the fix must keep binding the pattern's OWN names — and still mark them vals.
    run(
      """def call0(g: () => Int): Int = g()
        |val (p, q) = (10, 20)
        |println(call0(() => p + q))
        |""".stripMargin) shouldBe "30"
