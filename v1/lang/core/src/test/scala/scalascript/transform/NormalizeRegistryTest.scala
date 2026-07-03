package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir

/** Stage 9+/A.1 — Normalize consults `SourceLanguageRegistry` for
 *  non-scalascript fence tags.  Core's test classpath doesn't include
 *  any SourceLanguage plugin module, so this test exercises the
 *  fallback branch: an unknown fence tag returns the wrapping
 *  `EmbeddedBlock` shape (the registry was queried, returned None).
 *
 *  The "happy" branch — a plugin claims the tag and produces a
 *  custom fragment — lives in `cli/`'s test scope where the bundled
 *  `backend-scala-source` plugin is on the classpath. */
class NormalizeRegistryTest extends AnyFunSuite:

  test("non-scalascript fence with no registered plugin → EmbeddedBlock fallback"):
    val src =
      """# Test
        |
        |```toml
        |[section]
        |key = "value"
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(src))
    val firstContent = module.sections.head.content.head
    firstContent match
      case ir.Content.EmbeddedBlock("toml", body, _, _) =>
        assert(body.contains("key = \"value\""))
      case other =>
        fail(s"expected EmbeddedBlock(toml), got $other")
