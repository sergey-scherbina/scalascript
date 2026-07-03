package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi seq-74 regression — a cross-module function delegating to another
 *  module's ref-returning (String/collection) function must return the ref, not
 *  IntV(0). Root cause: VmCompiler typed every non-double user-fn CALL result as
 *  TInt (no TRef branch), so a String-returning callee surfaced as the raw long 0.
 *  Fix: JitPredicates.isRefReturning + VmCompiler calleeReturnsRef.
 *  Runs with the JIT on (default) so the compiled path is exercised. */
class JitCrossModuleRefReturnTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString.trim

  test("cross-module single-expr String delegation returns the String, not IntV(0)"):
    val dir = os.temp.dir(prefix = "ssc-jit-xmod-ref-")
    os.write(dir / "persons.ssc",
      """---
        |name: persons
        |exports:
        |  - normalizePersonHandle
        |---
        |# Persons
        |
        |```scalascript
        |def normalizePersonHandle(raw: String): String = raw.trim.toLowerCase
        |```
        |""".stripMargin)
    os.write(dir / "public_address.ssc",
      """---
        |name: public_address
        |exports:
        |  - normalizeBusiLocalPart
        |---
        |# Public address
        |
        |[normalizePersonHandle](persons.ssc)
        |
        |```scalascript
        |def normalizeBusiLocalPart(raw: String): String = normalizePersonHandle(raw)
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[normalizeBusiLocalPart](public_address.ssc)
        |
        |```scalascript
        |val lp = normalizeBusiLocalPart("Sig_Main")
        |println(lp + "@busi")
        |```
        |""".stripMargin)

    assert(run(dir, "main.ssc") == "sig_main@busi")

  test("transitive String delegation chain f→g→h also returns the String"):
    val dir = os.temp.dir(prefix = "ssc-jit-xmod-ref-chain-")
    os.write(dir / "h.ssc",
      """---
        |name: h
        |exports:
        |  - hh
        |---
        |# H
        |
        |```scalascript
        |def hh(raw: String): String = raw.toUpperCase
        |```
        |""".stripMargin)
    os.write(dir / "g.ssc",
      """---
        |name: g
        |exports:
        |  - gg
        |---
        |# G
        |
        |[hh](h.ssc)
        |
        |```scalascript
        |def gg(raw: String): String = hh(raw)
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[gg](g.ssc)
        |
        |```scalascript
        |def ff(raw: String): String = gg(raw)
        |println(ff("ok") + "!")
        |```
        |""".stripMargin)

    assert(run(dir, "main.ssc") == "OK!")

  test("numeric cross-module delegation is unaffected (still returns the Int)"):
    val dir = os.temp.dir(prefix = "ssc-jit-xmod-num-")
    os.write(dir / "num.ssc",
      """---
        |name: num
        |exports:
        |  - dbl
        |---
        |# Num
        |
        |```scalascript
        |def dbl(n: Int): Int = n + n
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[dbl](num.ssc)
        |
        |```scalascript
        |def quad(n: Int): Int = dbl(n) + dbl(n)
        |println(quad(5))
        |```
        |""".stripMargin)

    assert(run(dir, "main.ssc") == "20")
