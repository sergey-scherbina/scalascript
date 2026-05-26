package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

class JsGenIndexedDbTest extends AnyFunSuite:

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(js: String): String =
    val tmp = java.io.File.createTempFile("ssc-indexeddb-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  private val source =
    """# IndexedDB
      |
      |```scalascript
      |case class Draft(id: String, title: String, done: Boolean)
      |
      |val drafts = IndexedDb.store[Draft]("drafts", "test-db")
      |awaitClient(drafts.clear())
      |awaitClient(drafts.put(Draft("d1", "Plan", false)))
      |awaitClient(drafts.put(Draft("d2", "Ship", true)))
      |
      |val loadedOpt = awaitClient(drafts.get("d1"))
      |val loaded = loadedOpt.get
      |val rows = awaitClient(drafts.all())
      |val keys = awaitClient(drafts.keys())
      |awaitClient(drafts.remove("d2"))
      |val afterRemove = awaitClient(drafts.get("d2"))
      |
      |println(loaded.title + ":" + rows.size + ":" + keys.head + ":" + afterRemove.isEmpty)
      |```
      |""".stripMargin

  test("JS codegen lowers IndexedDb.store type argument to runtime type name"):
    val code = JsGen.generate(Parser.parse(source))

    assert(code.contains("""IndexedDb.store("drafts", "Draft", "test-db")"""))
    assert(code.contains("const drafts = IndexedDb.store"))
    assert(code.contains("await _dispatch(drafts, 'put'"))

  test("JS runtime stores typed objects through IndexedDb fallback"):
    assume(hasNode, "node not available")
    val js = JsRuntime + "\n" + JsGen.generate(Parser.parse(source))

    assert(runJs(js) == "Plan:2:d1:true")
