package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** CROSS-MODULE regression for the JS-backend supertype type-test bug (2026-06-15).
 *
 *  The single-file `SupertypeTypeTestJsTest` passed even while the real busi symptom
 *  persisted: there the trait (`TkNode`) and its subtypes live in `std/ui/nodes.ssc`
 *  (a `package:` module), while the `case h: TkNode` lives in `std/ui/lower.ssc` — and
 *  the JS backend emits each imported module with a fresh child `JsGen` via genImport,
 *  so the importer's matcher had no record of the imported subtype graph and fell back
 *  to the broken exact `_type === 'TkNode'` check. The subtype closure must therefore
 *  accumulate ACROSS the import boundary (and descend into `package:` wrapping objects).
 *
 *  These write a real two-file project and compile the importer through genImport (which
 *  reads + resolves the imported file), then run the emitted JS through node. */
class SupertypeTypeTestXModuleJsTest extends AnyFunSuite:

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runProject(dir: os.Path, entry: String): String =
    val mainModule = Parser.parse(os.read(dir / entry))
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : ''));"""
    val js = JsRuntime + "\n" + JsRuntimeAsync + "\n" +
             JsGen.generate(mainModule, baseDir = Some(dir)) + "\n" + flush
    val tmp = java.io.File.createTempFile("ssc-supertype-xmod-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath).redirectErrorStream(true).start()
    val out  = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  // A `package:` module of nodes — mirrors std/ui/nodes.ssc (compiles to a wrapping
  // object, so the subtype scan must descend into it).
  private def writeNodes(dir: os.Path): Unit =
    os.write(dir / "nodes.ssc",
      """---
        |name: nodes
        |package: demo.nodes
        |exports:
        |  - TkNode
        |  - HeadingNode
        |  - TextNode
        |---
        |# nodes
        |
        |```scalascript
        |sealed trait TkNode
        |case class HeadingNode(text: String) extends TkNode
        |case class TextNode(text: String) extends TkNode
        |```
        |""".stripMargin)

  test("case x: <imported sealed trait> matches a subtype — the busi card-header repro (cross-module)"):
    assume(hasNode, "node not available")
    val dir = os.temp.dir(prefix = "ssc-supertype-xmod-")
    writeNodes(dir)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[TkNode, HeadingNode, TextNode](nodes.ssc)
        |
        |```scalascript
        |def isTk(x: Any): String = x match
        |  case h: TkNode => "tk"
        |  case _         => "other"
        |println(isTk(HeadingNode("hi")))
        |println(isTk(TextNode("yo")))
        |println(isTk(42))
        |```
        |""".stripMargin)
    assert(runProject(dir, "main.ssc") == "tk\ntk\nother")

  test("case x: <imported intermediate trait> + enum subtype, transitive, cross-module"):
    assume(hasNode, "node not available")
    val dir = os.temp.dir(prefix = "ssc-supertype-xmod-enum-")
    os.write(dir / "events.ssc",
      """---
        |name: events
        |package: demo.events
        |exports:
        |  - Event
        |  - CoreEvent
        |  - LedgerEvent
        |---
        |# events
        |
        |```scalascript
        |sealed trait Event
        |sealed trait CoreEvent extends Event
        |enum LedgerEvent extends CoreEvent:
        |  case AccountCreated(code: String)
        |  case Posted(amount: Int)
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |
        |[Event, CoreEvent, LedgerEvent](events.ssc)
        |
        |```scalascript
        |def isCore(e: Any): Boolean = e match
        |  case c: CoreEvent => true
        |  case _            => false
        |def isEvent(e: Any): Boolean = e match
        |  case x: Event => true
        |  case _        => false
        |println(isCore(LedgerEvent.AccountCreated("1000")))
        |println(isEvent(LedgerEvent.Posted(5)))
        |println(isCore("nope"))
        |```
        |""".stripMargin)
    assert(runProject(dir, "main.ssc") == "true\ntrue\nfalse")
