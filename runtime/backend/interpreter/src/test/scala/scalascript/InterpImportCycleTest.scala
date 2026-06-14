package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi-reported (p5 dispatch.ssc decomposition) — a true module import CYCLE
 *  (A→B→A, e.g. a sub-module importing back from the facade that imports it) must
 *  fail with a legible "Import cycle detected" error naming the path, **not** a bare
 *  `StackOverflowError`. The module loader's `moduleCache` dedup only catches acyclic
 *  diamonds (it inserts after the body runs, so a still-loading module is absent from
 *  the cache and a cyclic re-import recurses forever); `moduleLoading` tracks paths on
 *  the resolution stack to catch the cycle. Acyclic re-export must stay unaffected.
 *  Spec: specs/import-cycle-diagnostic.md. */
class InterpImportCycleTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString

  private def assertCycleNotOverflow(thunk: => Unit): Unit =
    val ex = intercept[Throwable](thunk)
    assert(!ex.isInstanceOf[StackOverflowError],
      "import cycle overflowed the stack instead of reporting a cycle")
    val msg = Option(ex.getMessage).getOrElse("")
    assert(msg.toLowerCase.contains("cycle"),
      s"expected an 'Import cycle detected' message, got: ${ex.getClass.getName}: $msg")

  test("a direct 2-cycle (a <-> b) reports a cycle, not a StackOverflowError"):
    val dir = os.temp.dir(prefix = "ssc-import-cycle-")
    // a imports b; b imports a — a true cycle
    os.write(dir / "a.ssc",
      """---
        |name: a
        |exports:
        |  - aFn
        |---
        |# a
        |[bFn](b.ssc)
        |```scalascript
        |def aFn(): Int = bFn() + 1
        |```
        |""".stripMargin)
    os.write(dir / "b.ssc",
      """---
        |name: b
        |exports:
        |  - bFn
        |---
        |# b
        |[aFn](a.ssc)
        |```scalascript
        |def bFn(): Int = 10
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |[aFn](a.ssc)
        |```scalascript
        |println(aFn())
        |```
        |""".stripMargin)
    assertCycleNotOverflow(run(dir, "main.ssc"))

  test("a facade<->leaf cycle (sub-module imports back from its facade) reports a cycle"):
    val dir = os.temp.dir(prefix = "ssc-import-cycle-facade-")
    os.write(dir / "b.ssc",
      """---
        |name: b
        |exports:
        |  - bFn
        |---
        |# b
        |```scalascript
        |def bFn(): Int = 10
        |```
        |""".stripMargin)
    // a imports bFn from the FACADE (not from b directly) -> facade -> a -> facade
    os.write(dir / "a.ssc",
      """---
        |name: a
        |exports:
        |  - aFn
        |---
        |# a
        |[bFn](facade.ssc)
        |```scalascript
        |def aFn(): Int = bFn() + 1
        |```
        |""".stripMargin)
    os.write(dir / "facade.ssc",
      """---
        |name: facade
        |exports:
        |  - aFn
        |  - bFn
        |---
        |# facade
        |[aFn](a.ssc) [bFn](b.ssc)
        |```scalascript
        |```
        |""".stripMargin)
    os.write(dir / "user.ssc",
      """# User
        |[aFn](facade.ssc)
        |```scalascript
        |println(aFn())
        |```
        |""".stripMargin)
    assertCycleNotOverflow(run(dir, "user.ssc"))

  test("an acyclic re-export (user -> facade -> leaf) still resolves and computes"):
    val dir = os.temp.dir(prefix = "ssc-import-reexport-")
    os.write(dir / "leaf.ssc",
      """---
        |name: leaf
        |exports:
        |  - leafFn
        |---
        |# leaf
        |```scalascript
        |def leafFn(): Int = 42
        |```
        |""".stripMargin)
    // facade re-exports leafFn (imports it AND lists it in exports); leaf does NOT
    // import back, so this is acyclic and must work exactly as before.
    os.write(dir / "facade.ssc",
      """---
        |name: facade
        |exports:
        |  - leafFn
        |---
        |# facade
        |[leafFn](leaf.ssc)
        |```scalascript
        |```
        |""".stripMargin)
    os.write(dir / "user.ssc",
      """# User
        |[leafFn](facade.ssc)
        |```scalascript
        |println(leafFn())
        |```
        |""".stripMargin)
    val out = run(dir, "user.ssc")
    assert(out.linesIterator.contains("42"), s"expected re-exported leafFn() = 42; output:\n$out")
