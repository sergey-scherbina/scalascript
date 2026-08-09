package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Importing a module BY ITS PACKAGE ROOT when that module declares `exports:`.
 *
 *  A package name is a NAMESPACE, not a member: you export names IN it, you do not export it. The
 *  interpreter used to check every binding against `exports:`, including the root, and so refused
 *  `[p](lib.ssc)` for a module whose manifest says `package: p` — while the native lane bound it
 *  and ran. Decided 2026-08-09 in favour of the native reading
 *  (BUGS.md `package-root-import-needs-an-exports-entry-on-int`).
 *
 *  The second test is what keeps this honest. Exempting the root would be a hole if the gate were
 *  protecting anything, so it pins that ORDINARY bindings are still refused — the exemption is the
 *  root and nothing else. */
class PackageRootImportTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parseFile(dir / entry))
    ps.flush()
    buf.toString.trim

  private def lib(dir: os.Path): Unit =
    os.write(dir / "lib.ssc",
      """---
        |name: lib
        |package: p
        |exports:
        |  - shown
        |---
        |# Lib
        |
        |```scalascript
        |def shown()  = "shown"
        |def hidden() = "hidden"
        |```
        |""".stripMargin)

  test("a module can be imported by its package root without listing the root in `exports:`"):
    val dir = os.temp.dir(prefix = "ssc-package-root-")
    try
      lib(dir)
      os.write(dir / "main.ssc",
        """# Main
          |
          |[p](lib.ssc)
          |
          |```scalascript
          |println(p.shown())
          |```
          |""".stripMargin)
      assert(run(dir, "main.ssc") == "shown")
    finally os.remove.all(dir)

  test("an ordinary non-exported member is STILL refused — the exemption is the root alone"):
    val dir = os.temp.dir(prefix = "ssc-package-root-neg-")
    try
      lib(dir)
      os.write(dir / "main.ssc",
        """# Main
          |
          |[hidden](lib.ssc)
          |
          |```scalascript
          |println(hidden())
          |```
          |""".stripMargin)
      val err = intercept[Exception](run(dir, "main.ssc"))
      assert(err.getMessage.contains("is not exported by"), err.getMessage)
    finally os.remove.all(dir)
