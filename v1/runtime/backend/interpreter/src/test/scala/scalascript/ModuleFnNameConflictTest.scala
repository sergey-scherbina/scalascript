package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi-p3-module-fn-name-conflict — two imported modules defining the same
 *  function name. Policy: last import wins + warning.
 *  Spec: specs/import-name-conflict-policy.md */
class ModuleFnNameConflictTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): (String, Interpreter) =
    val buf = java.io.ByteArrayOutputStream()
    val ps = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, baseDir = Some(dir))
    interp.run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    (buf.toString.trim, interp)

  private def writeHtmlEscPair(dir: os.Path): Unit =
    os.write(dir / "fmt_a.ssc",
      """---
        |name: fmt_a
        |exports:
        |  - htmlEsc
        |---
        |# A
        |
        |```scalascript
        |def htmlEsc(s: String): String = "A:" + s
        |```
        |""".stripMargin)
    os.write(dir / "fmt_b.ssc",
      """---
        |name: fmt_b
        |exports:
        |  - htmlEsc
        |---
        |# B
        |
        |```scalascript
        |def htmlEsc(s: String): String = "B:" + s
        |```
        |""".stripMargin)

  test("two modules exporting the same fn name — last import wins + warning recorded"):
    val dir = os.temp.dir(prefix = "ssc-mfc-")
    writeHtmlEscPair(dir)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[htmlEsc](fmt_a.ssc)
        |[htmlEsc](fmt_b.ssc)
        |
        |```scalascript
        |println(htmlEsc("x"))
        |```
        |""".stripMargin)

    val (out, interp) = run(dir, "main.ssc")
    assert(out == "B:x", s"expected last import (fmt_b) to win, got: $out")
    assert(interp.importNameConflictWarnings.contains("htmlEsc"),
      "the cross-module fn-name conflict must be recorded as a warning")

  test("no warning when the imported name does not collide"):
    val dir = os.temp.dir(prefix = "ssc-mfc-noconflict-")
    os.write(dir / "fmt_a.ssc",
      """---
        |name: fmt_a
        |exports:
        |  - escA
        |---
        |# A
        |
        |```scalascript
        |def escA(s: String): String = "A:" + s
        |```
        |""".stripMargin)
    os.write(dir / "fmt_b.ssc",
      """---
        |name: fmt_b
        |exports:
        |  - escB
        |---
        |# B
        |
        |```scalascript
        |def escB(s: String): String = "B:" + s
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[escA](fmt_a.ssc)
        |[escB](fmt_b.ssc)
        |
        |```scalascript
        |println(escA("x") + escB("y"))
        |```
        |""".stripMargin)

    val (out, interp) = run(dir, "main.ssc")
    assert(out == "A:xB:y")
    assert(interp.importNameConflictWarnings.isEmpty, "no collision → no warning")

  test("re-importing the same name from the same module does not warn"):
    val dir = os.temp.dir(prefix = "ssc-mfc-idem-")
    writeHtmlEscPair(dir)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[htmlEsc](fmt_a.ssc)
        |[htmlEsc](fmt_a.ssc)
        |
        |```scalascript
        |println(htmlEsc("x"))
        |```
        |""".stripMargin)

    val (out, interp) = run(dir, "main.ssc")
    assert(out == "A:x")
    assert(interp.importNameConflictWarnings.isEmpty,
      "idempotent re-import of the same module is not a conflict")
