package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** A module's `exports:` may legitimately name things that have no runtime VALUE:
 *  a `type X = …` alias (erased) and a method declared inside `extension (r: T)`
 *  (it belongs to the type, not to module scope). The interpreter's import-binding
 *  loop looked up a value for every named binding and raised
 *  `'X' not found in M` for both shapes — for names the module DEFINES and EXPORTS.
 *
 *  Measured on the corpus golden lane before the fix: `Pass`
 *  (`std/dsl/passes.ssc:33`), `ParseErrors` (`std/parsing/recovery.ssc:132`),
 *  `withIndent` (`std/parsing/layout.ssc:210`). Each blocked its case on int, and a
 *  case with no golden is SKIPped on EVERY lane, so this was hiding v2 too.
 *  (std-import-resolver-blind-to-type-alias-and-extension.)
 */
class ModuleImportTypeAliasExtensionTest extends AnyFunSuite:

  private def withModules(body: os.Path => Unit): Unit =
    val dir = os.temp.dir(prefix = "ssc-import-shapes")
    try body(dir)
    finally os.remove.all(dir)

  private val moduleSrc =
    """---
      |name: shapes-mod
      |package: shapes.m
      |exports:
      |  - Alias
      |  - Rec
      |  - bump
      |  - plainDef
      |---
      |
      |# shapes
      |
      |```scalascript
      |type Alias = List[Int]
      |
      |case class Rec(n: Int)
      |
      |def plainDef(n: Int): Int = n + 1
      |
      |extension (r: Rec)
      |  def bump(k: Int): Int = r.n + k
      |```
      |""".stripMargin

  private def run(dir: os.Path, consumer: String): String =
    os.write.over(dir / "m.ssc", moduleSrc)
    os.write.over(dir / "use.ssc", consumer)
    val out = new java.io.ByteArrayOutputStream
    val interp = Interpreter(new java.io.PrintStream(out, true, "UTF-8"), Some(dir))
    interp.run(Parser.parse(os.read(dir / "use.ssc")))
    out.toString("UTF-8")

  test("a `type` alias can be named in an import list"):
    withModules { dir =>
      val out = run(dir,
        """```scalascript
          |[Alias, plainDef](./m.ssc)
          |println(plainDef(1))
          |```
          |""".stripMargin)
      assert(out.trim == "2")
    }

  test("an extension method can be named in an import list, and still dispatches"):
    withModules { dir =>
      val out = run(dir,
        """```scalascript
          |[Rec, bump](./m.ssc)
          |println(Rec(5).bump(3))
          |```
          |""".stripMargin)
      // 8 proves more than "the import did not throw": the extension really is
      // registered on the imported type in the importing interpreter.
      assert(out.trim == "8")
    }

  test("a name the module neither defines nor exports still fails"):
    withModules { dir =>
      val error = intercept[Exception](run(dir,
        """```scalascript
          |[Nope](./m.ssc)
          |println(1)
          |```
          |""".stripMargin))
      // The guard must stay a guard — accepting alias/extension names must not
      // turn every unresolved import into a silent no-op.
      assert(error.getMessage.contains("Nope"),
        s"expected a diagnostic naming the missing binding, got: ${error.getMessage}")
    }
