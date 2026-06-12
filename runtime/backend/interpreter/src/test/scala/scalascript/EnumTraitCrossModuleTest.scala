package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi seq-124/125 regression — the enum → trait hierarchy fix (seq-120/121) must
 *  also work **across an import boundary**: an instance constructed from an
 *  *imported* enum-case constructor carries the full parent chain (type-test by an
 *  intermediate supertype) AND dispatches concrete methods defined on a trait it
 *  extends. The same-file cases are in `BugReproTest`; these are 2-file (the import
 *  merge of `parentTypes` + `typeMethods`). */
class EnumTraitCrossModuleTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString.trim

  private def writeEvmod(dir: os.Path): Unit =
    os.write(dir / "evmod.ssc",
      """---
        |name: evmod
        |exports:
        |  - Event
        |  - CoreEvent
        |  - AccountCreated
        |  - eventIsCore
        |---
        |# evmod
        |
        |```scalascript
        |trait Event:
        |  def kind: String = "k:" + (this match { case AccountCreated(c) => c; case _ => "?" })
        |sealed trait CoreEvent extends Event
        |enum LedgerEvent extends CoreEvent:
        |  case AccountCreated(code: String)
        |def eventIsCore(e: Event): Boolean = e match { case _: CoreEvent => true; case _ => false }
        |```
        |""".stripMargin)

  test("type-test by an intermediate trait works across an import boundary (busi seq-124)"):
    val dir = os.temp.dir(prefix = "ssc-enum-trait-xmod-")
    writeEvmod(dir)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[Event, AccountCreated, eventIsCore](evmod.ssc)
        |
        |```scalascript
        |val a: Event = AccountCreated("1000")
        |println(eventIsCore(a))
        |```
        |""".stripMargin)
    assert(run(dir, "main.ssc") == "true")

  test("a concrete trait method dispatches on an imported enum-case instance (busi seq-125)"):
    val dir = os.temp.dir(prefix = "ssc-enum-trait-xmod-m-")
    writeEvmod(dir)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[Event, AccountCreated](evmod.ssc)
        |
        |```scalascript
        |val a: Event = AccountCreated("1000")
        |println(a.kind)
        |```
        |""".stripMargin)
    assert(run(dir, "main.ssc") == "k:1000")
