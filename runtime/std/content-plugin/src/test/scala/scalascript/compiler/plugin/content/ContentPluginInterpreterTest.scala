package scalascript.compiler.plugin.content

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{InterpretError, Interpreter, Value}
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
      case docInst @ Value.InstanceV("DocumentContent", docFields) =>
        assert(docInst.fieldNames.toList == List("manifest", "title", "description", "attrs", "sections", "blocks"))
        assert(docInst.fieldsArr(1) == Value.OptionV(Value.StringV("Pricing")))
        assert(docFields("title") == Value.OptionV(Value.StringV("Pricing")))
        val sections = docFields("sections") match
          case Value.ListV(items) => items
          case other => fail(s"expected sections list, got $other")
        assert(sections.size == 1)
        sections.head match
          case sectionInst @ Value.InstanceV("SectionContent", sectionFields) =>
            assert(sectionInst.fieldNames.toList == List("id", "level", "title", "attrs", "blocks", "children"))
            assert(sectionInst.fieldsArr(2) == Value.StringV("Pricing"))
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

  test("contentData exposes structured fenced data by explicit id"):
    val source =
      """---
        |name: content-data-runtime-test
        |---
        |
        |# Pricing
        |
        |```yaml @id=plans-data
        |plans:
        |  - id: starter
        |    price: 19
        |  - id: pro
        |    price: 49
        |```
        |
        |```scala @id=not-data
        |val ignored = 1
        |```
        |
        |[contentData](std/content.ssc)
        |
        |```scala
        |List(
        |  contentData("plans-data").isDefined.toString,
        |  contentData("not-data").isDefined.toString,
        |  contentData("missing").isDefined.toString
        |)
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))

    interp.lastResult match
      case Value.ListV(List(Value.StringV("true"), Value.StringV("false"), Value.StringV("false"))) =>
        succeed
      case other =>
        fail(s"expected contentData defined/missing booleans, got $other")

  test("contentSection contentBlock and contentPlainText expose selected Markdown regions"):
    val source =
      """---
        |name: content-lookup-runtime-test
        |---
        |
        |# Pricing
        |
        |Intro paragraph.
        |
        |## Plans {#plans}
        |
        |<!-- @meta id=hero-copy -->
        |Simple plans for **small teams** with `predictable` billing.
        |
        |<!-- @meta id=plan-list -->
        |- Starter
        |- Pro
        |
        |```yaml @id=plans-data
        |plans:
        |  - id: starter
        |  - id: pro
        |```
        |
        |## Feature Matrix
        |
        |Generated id section.
        |
        |[contentSection, contentBlock, contentPlainText, contentData](std/content.ssc)
        |
        |```scala
        |val plans = contentSection("plans").get
        |val generated = contentSection("feature-matrix").get
        |val hero = contentBlock("hero-copy").get
        |val planList = contentBlock("plan-list").get
        |val dataBlock = contentBlock("plans-data").get
        |
        |List(
        |  contentSection("feature-matrix").isDefined.toString,
        |  contentSection("missing").isDefined.toString,
        |  contentBlock("missing").isDefined.toString,
        |  contentPlainText(plans),
        |  contentPlainText(hero),
        |  contentPlainText(planList),
        |  contentPlainText(dataBlock),
        |  contentPlainText(generated),
        |  contentData("plans-data").isDefined.toString
        |)
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))

    interp.lastResult match
      case Value.ListV(values) =>
        val strings = values.collect { case Value.StringV(value) => value }
        assert(strings.size == 9)
        assert(strings.take(3) == List("true", "false", "false"))
        assert(strings(3).contains("Plans"))
        assert(strings(3).contains("Simple plans for small teams with `predictable` billing."))
        assert(strings(3).contains("- Starter\n- Pro"))
        assert(strings(3).contains("yaml: plans:"))
        assert(strings(4) == "Simple plans for small teams with `predictable` billing.")
        assert(strings(5) == "- Starter\n- Pro")
        assert(strings(6).startsWith("yaml: plans:"))
        assert(strings(7).contains("Feature Matrix"))
        assert(strings(7).contains("Generated id section."))
        assert(strings(8) == "true")
      case other =>
        fail(s"expected content lookup/plain-text strings, got $other")

  test("contentData reports duplicate structured data ids"):
    val source =
      """---
        |name: content-data-duplicate-test
        |---
        |
        |# Demo
        |
        |```yaml @id=same
        |value: 1
        |```
        |
        |```json @id=same
        |{"value": 2}
        |```
        |
        |[contentData](std/content.ssc)
        |
        |```scala
        |contentData("same")
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    val err = intercept[InterpretError]:
      interp.run(Parser.parse(source))
    assert(err.getMessage.contains("contentData: duplicate structured data id 'same'"))

  test("contentBlock reports duplicate content block ids"):
    val source =
      """---
        |name: content-block-duplicate-test
        |---
        |
        |# Demo
        |
        |<!-- @meta id=same -->
        |First copy.
        |
        |<!-- @meta id=same -->
        |Second copy.
        |
        |[contentBlock](std/content.ssc)
        |
        |```scala
        |contentBlock("same")
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    val err = intercept[InterpretError]:
      interp.run(Parser.parse(source))
    assert(err.getMessage.contains("contentBlock: duplicate block id 'same'"))

  test("contentPlainText reports unsupported values"):
    val source =
      """---
        |name: content-plain-text-unsupported-test
        |---
        |
        |# Demo
        |
        |[contentPlainText](std/content.ssc)
        |
        |```scala
        |contentPlainText(123)
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    val err = intercept[InterpretError]:
      interp.run(Parser.parse(source))
    assert(err.getMessage.contains("contentPlainText: expected SectionContent or ContentBlock"))
