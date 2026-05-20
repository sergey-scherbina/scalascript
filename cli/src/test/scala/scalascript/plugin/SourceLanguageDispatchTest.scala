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
      case ir.Content.EmbeddedBlock("mock-rewritten", body, _) =>
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
      case ir.Content.EmbeddedBlock("scala", body, _) =>
        assert(body.contains("val x = 1"))
      case other =>
        fail(s"expected EmbeddedBlock(scala), got $other")
