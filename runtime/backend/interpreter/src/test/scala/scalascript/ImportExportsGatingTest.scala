package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser

/** A module's `exports:` list gates what `[x](M)` can import by name — mirroring the
 *  JS/JVM backends. A name reachable only transitively (defined elsewhere, or in childCtx
 *  for call-time resolution) but not listed in M's `exports:` is not importable from M.
 *  A module that declares no exports stays permissive (legacy). The transitive call-time
 *  dump — an exported fn calling its own non-exported helpers — is unaffected. */
class ImportExportsGatingTest extends AnyFunSuite with Matchers:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush(); buf.toString.trim

  test("importing an exported name resolves"):
    val dir = os.temp.dir(prefix = "ssc-exp-ok-")
    os.write(dir / "m.ssc",
      """---
        |name: m
        |exports:
        |  - pub
        |---
        |# m
        |```scalascript
        |def pub(): String = "P"
        |def priv(): String = "X"
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      "# main\n[pub](m.ssc)\n```scalascript\nprintln(pub())\n```\n")
    run(dir, "main.ssc") shouldBe "P"

  test("importing a defined-but-not-exported name is rejected"):
    val dir = os.temp.dir(prefix = "ssc-exp-priv-")
    os.write(dir / "m.ssc",
      """---
        |name: m
        |exports:
        |  - pub
        |---
        |# m
        |```scalascript
        |def pub(): String = "P"
        |def priv(): String = "X"
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      "# main\n[priv](m.ssc)\n```scalascript\nprintln(priv())\n```\n")
    val e = intercept[InterpretError](run(dir, "main.ssc"))
    e.getMessage should include ("priv")
    e.getMessage.toLowerCase should include ("not exported")

  test("a module that declares no exports stays permissive"):
    val dir = os.temp.dir(prefix = "ssc-exp-none-")
    os.write(dir / "m.ssc",
      "# m\n```scalascript\ndef anyName(): String = \"A\"\n```\n")
    os.write(dir / "main.ssc",
      "# main\n[anyName](m.ssc)\n```scalascript\nprintln(anyName())\n```\n")
    run(dir, "main.ssc") shouldBe "A"

  test("a name reachable only transitively through a facade is NOT importable from the facade"):
    // base exports `leaf`; facade imports `leaf` but does NOT re-export it.
    // Importing `leaf` from the facade must fail (the busi queryPit37Xml case).
    val dir = os.temp.dir(prefix = "ssc-exp-facade-")
    os.write(dir / "base.ssc",
      """---
        |name: base
        |exports:
        |  - leaf
        |---
        |# base
        |```scalascript
        |def leaf(): String = "L"
        |```
        |""".stripMargin)
    os.write(dir / "facade.ssc",
      """---
        |name: facade
        |exports:
        |  - viaFacade
        |---
        |# facade
        |[leaf](base.ssc)
        |```scalascript
        |def viaFacade(): String = leaf()
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      "# main\n[leaf](facade.ssc)\n```scalascript\nprintln(leaf())\n```\n")
    val e = intercept[InterpretError](run(dir, "main.ssc"))
    e.getMessage should include ("leaf")
    e.getMessage.toLowerCase should include ("not exported")

  test("re-exporting (listing the imported name in exports) makes it importable"):
    val dir = os.temp.dir(prefix = "ssc-exp-reexport-")
    os.write(dir / "base.ssc",
      """---
        |name: base
        |exports:
        |  - leaf
        |---
        |# base
        |```scalascript
        |def leaf(): String = "L"
        |```
        |""".stripMargin)
    os.write(dir / "facade.ssc",
      """---
        |name: facade
        |exports:
        |  - leaf
        |---
        |# facade
        |[leaf](base.ssc)
        |```scalascript
        |def own(): String = "O"
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      "# main\n[leaf](facade.ssc)\n```scalascript\nprintln(leaf())\n```\n")
    run(dir, "main.ssc") shouldBe "L"

  test("exported fn calling its own non-exported helper still works (call-time dump intact)"):
    val dir = os.temp.dir(prefix = "ssc-exp-calltime-")
    os.write(dir / "dep.ssc",
      """---
        |name: dep
        |exports:
        |  - outer
        |---
        |# dep
        |```scalascript
        |def helper(): String = "H"
        |def outer(): String = helper()
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      "# main\n[outer](dep.ssc)\n```scalascript\nprintln(outer())\n```\n")
    run(dir, "main.ssc") shouldBe "H"
