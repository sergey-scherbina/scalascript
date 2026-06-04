package scalascript.compiler.plugin.content

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

class ContentPluginInterpreterTest extends AnyFunSuite:

  private lazy val repoRoot: os.Path =
    var p = os.pwd
    while !os.exists(p / "build.sbt") do
      val up = p / os.up
      if up == p then
        throw RuntimeException(s"could not locate repo root walking up from ${os.pwd}")
      p = up
    p

  test("contentDocument exposes parsed Markdown content to interpreted code"):
    val source =
      """---
        |name: content-runtime-test
        |---
        |
        |# Pricing {#pricing route=/pricing}
        |
        |Intro paragraph.
        |
        |[contentDocument](std/content.ssc)
        |
        |```scala
        |contentDocument()
        |```
        |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val interp = Interpreter(
      out = java.io.PrintStream(buf, true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))

    interp.lastResult match
      case Value.InstanceV("DocumentContent", docFields) =>
        assert(docFields("title") == Value.OptionV(Value.StringV("Pricing")))
        val sections = docFields("sections") match
          case Value.ListV(items) => items
          case other => fail(s"expected sections list, got $other")
        assert(sections.size == 1)
        sections.head match
          case Value.InstanceV("SectionContent", sectionFields) =>
            assert(sectionFields("id") == Value.StringV("pricing"))
            assert(sectionFields("title") == Value.StringV("Pricing"))
            assert(sectionFields("attrs") == Value.MapV(Map(
              Value.StringV("route") -> Value.InstanceV("Str", Map("value" -> Value.StringV("/pricing")))
            )))
            val blocks = sectionFields("blocks") match
              case Value.ListV(items) => items
              case other => fail(s"expected section blocks list, got $other")
            assert(blocks.count {
              case Value.InstanceV("Paragraph", _) => true
              case _ => false
            } == 1)
          case other => fail(s"expected SectionContent, got $other")
      case other => fail(s"expected DocumentContent, got $other")
