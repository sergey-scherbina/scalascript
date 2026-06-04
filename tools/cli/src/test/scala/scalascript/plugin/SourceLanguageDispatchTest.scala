package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.ir

/** Stage 9+/A.1 happy-path: with a `SourceLanguage` plugin registered
 *  for the `mock` fence tag, Normalize dispatches through the plugin
 *  and the plugin's distinctive output appears in the IR. */
class SourceLanguageDispatchTest extends AnyFunSuite:

  test("Normalize routes mock fence through TestSourceLanguage.compileBlock"):
    val src =
      """# Test
        |
        |```mock
        |hello world
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(src))
    val firstContent = module.sections.head.content.head
    firstContent match
      case ir.Content.EmbeddedBlock("mock-rewritten", body, _, _) =>
        // Distinctive [mock]- prefix proves the plugin ran (the fallback
        // would have produced EmbeddedBlock("mock", "hello world\n")).
        assert(body.contains("[mock]") && body.contains("hello world"))
      case other =>
        fail(s"expected EmbeddedBlock(mock-rewritten) from TestSourceLanguage, got $other")

  test("bundled scala SourceLanguage plugin also intercepts `scala` blocks"):
    // ScalaSourceLanguage produces EmbeddedBlock(scala, source) which
    // matches Normalize's fallback shape exactly — observable difference
    // for the bundled plugin would require the plugin to do something
    // beyond passthrough.  Sanity check: a `scala` fence still
    // produces a NormalizedBlock that round-trips identically.
    val src =
      """# Test
        |
        |```scala
        |val x = 1
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(src))
    module.sections.head.content.head match
      case ir.Content.EmbeddedBlock("scala", body, _, _) =>
        assert(body.contains("val x = 1"))
      case other =>
        fail(s"expected EmbeddedBlock(scala), got $other")

  test("bundled SQL SourceLanguage preserves bind-aware SqlBlock routing"):
    val src =
      """# Test
        |
        |```sql @db=reports @side=client
        |select * from users where id = ${userId}
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(src))
    module.sections.head.content.head match
      case sql: ir.Content.SqlBlock =>
        assert(sql.dbName.contains("reports"))
        assert(sql.side == ir.SqlSide.Client)
        assert(sql.binds == List("userId"))
      case other =>
        fail(s"expected SqlBlock from bundled sql SourceLanguage, got $other")

  test("bundled Transaction SourceLanguage splits statements into TransactionBlock"):
    val src =
      """# Test
        |
        |```transaction @db=ops
        |insert into log values (1); insert into log values (2)
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(src))
    module.sections.head.content.head match
      case tx: ir.Content.TransactionBlock =>
        assert(tx.dbName.contains("ops"), s"expected dbName=ops, got ${tx.dbName}")
        assert(tx.sources.length == 2, s"expected 2 statements, got ${tx.sources.length}")
      case other =>
        fail(s"expected TransactionBlock from bundled transaction SourceLanguage, got $other")

  test("bundled JavaScript and XML SourceLanguages intercept aliases and canonical tags"):
    val src =
      """# Test
        |
        |```js
        |console.log("ok")
        |```
        |
        |```xml
        |<root/>
        |```
        |""".stripMargin
    val content = Normalize(Parser.parse(src)).sections.head.content
    assert(content.exists {
      case ir.Content.EmbeddedBlock("javascript", body, _, _) => body.contains("console.log")
      case _                                               => false
    })
    assert(content.exists {
      case ir.Content.EmbeddedBlock("xml", body, _, _) => body.contains("<root/>")
      case _                                        => false
    })
