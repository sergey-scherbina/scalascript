package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.BackendTransportKind
import scalascript.parser.Parser

class V2DefaultSwitchTest extends AnyFunSuite:

  private def module(frontMatter: String = "") =
    val header =
      if frontMatter.trim.isEmpty then ""
      else s"---\n$frontMatter---\n\n"
    Parser.parse(
      header +
        """# Test
          |
          |```scalascript
          |println("ok")
          |```
          |""".stripMargin
    )

  test("plain modules use the v2 default runner"):
    assert(shouldUseV2DefaultRunner(module()))

  test("explicit front matter lanes stay off the v2 default runner"):
    val frontMatters = List(
      "backend: spark\n",
      "frontend: electron\n",
      "target: desktop-jvm\n",
      "transport: in-process\n",
      "fullstack:\n  transport: in-process\n"
    )
    frontMatters.foreach { fm =>
      assert(!shouldUseV2DefaultRunner(module(fm)), s"front matter should stay off v2 default:\n$fm")
    }

  test("plain run flags allow v2 default only when no explicit lane is selected"):
    assert(runFlagsAllowV2Default(None, None, None, None, None, None, None, None, None))
    assert(!runFlagsAllowV2Default(Some("jvm"), None, None, None, None, None, None, None, None))
    assert(!runFlagsAllowV2Default(None, Some("react"), None, None, None, None, None, None, None))
    assert(!runFlagsAllowV2Default(None, None, Some("spark"), None, None, None, None, None, None))
    assert(!runFlagsAllowV2Default(None, None, None, Some("server"), None, None, None, None, None))
    assert(!runFlagsAllowV2Default(None, None, None, None, Some("http://localhost"), None, None, None, None))
    assert(!runFlagsAllowV2Default(None, None, None, None, None, Some(BackendTransportKind.InProcess), None, None, None))
    assert(!runFlagsAllowV2Default(None, None, None, None, None, None, Some("127.0.0.1"), None, None))
    assert(!runFlagsAllowV2Default(None, None, None, None, None, None, None, Some(8080), None))
    assert(!runFlagsAllowV2Default(None, None, None, None, None, None, None, None, Some(true)))
