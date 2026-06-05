package scalascript.compiler.plugin.content

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.SsccFormat
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

  test("contentCurrentSection exposes enclosing section metadata and sibling blocks"):
    val source =
      """---
        |name: content-current-section-runtime-test
        |---
        |
        |# Details {#details route=/details}
        |
        |Intro paragraph.
        |
        |- Starter
        |- Pro
        |
        |[contentCurrentSection, contentPlainText](std/content.ssc)
        |
        |```scala
        |val firstCurrent = contentCurrentSection()
        |```
        |
        |```scala
        |List(
        |  firstCurrent.id,
        |  contentCurrentSection().id,
        |  contentPlainText(contentCurrentSection())
        |)
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))

    val firstFields = instanceFields(interp.globalsView("firstCurrent"), "SectionContent")
    assert(firstFields("id") == Value.StringV("details"))
    assert(firstFields("title") == Value.StringV("Details"))
    firstFields("attrs") match
      case Value.MapV(attrs) =>
        attrs.get(Value.StringV("route")) match
          case Some(Value.InstanceV("Str", fields)) => assert(fields("value") == Value.StringV("/details"))
          case other => fail(s"expected route string attr, got $other")
      case other => fail(s"expected attrs map, got $other")
    val blocks = listField(firstFields, "blocks", "SectionContent.blocks")
    assert(blocks.exists {
      case Value.InstanceV("Paragraph", _) => true
      case _ => false
    })
    assert(blocks.exists {
      case Value.InstanceV("BulletList", _) => true
      case _ => false
    })

    interp.lastResult match
      case Value.ListV(List(Value.StringV(firstId), Value.StringV(secondId), Value.StringV(text))) =>
        assert(firstId == "details")
        assert(secondId == "details")
        assert(text.contains("Details"))
        assert(text.contains("Intro paragraph."))
        assert(text.contains("- Starter\n- Pro"))
      case other =>
        fail(s"expected current-section ids and plain text, got $other")

  test("contentCurrentSection uses nearest nested generated section and caller execution scope"):
    val source =
      """---
        |name: content-current-section-caller-test
        |---
        |
        |# Define
        |
        |[contentCurrentSection](std/content.ssc)
        |
        |```scala
        |def sectionIdAtCall(): String = contentCurrentSection().id
        |val definitionSectionId = contentCurrentSection().id
        |```
        |
        |# Parent
        |
        |## Feature Matrix
        |
        |Generated id section.
        |
        |```scala
        |List(
        |  definitionSectionId,
        |  sectionIdAtCall(),
        |  contentCurrentSection().id,
        |  contentCurrentSection().title
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
      case Value.ListV(List(
            Value.StringV(definitionId),
            Value.StringV(calledId),
            Value.StringV(currentId),
            Value.StringV(currentTitle)
          )) =>
        assert(definitionId == "define")
        assert(calledId == "feature-matrix")
        assert(currentId == "feature-matrix")
        assert(currentTitle == "Feature Matrix")
      case other =>
        fail(s"expected dynamic caller section ids, got $other")

  test("contentCurrentSection reports headingless code and does not leak prior section"):
    val seeded =
      """---
        |name: content-current-section-leak-seed-test
        |---
        |
        |# Seed
        |
        |[contentCurrentSection](std/content.ssc)
        |
        |```scala
        |contentCurrentSection().id
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(seeded))
    assert(interp.lastResult == Value.StringV("seed"))

    val headingless =
      """```scala
        |contentCurrentSection()
        |```
        |""".stripMargin

    val err = intercept[InterpretError]:
      interp.runSections(Parser.parse(headingless))
    assert(err.getMessage.contains("contentCurrentSection() is only available"))

  test("contentMetadata reads content frontmatter by dot path"):
    val source =
      """---
        |name: content-metadata-runtime-test
        |content:
        |  defaultRenderer: toolkit
        |  theme:
        |    density: compact
        |  flags:
        |    showBeta: true
        |  limits:
        |    retries: 3
        |  tags:
        |    - alpha
        |    - beta
        |  nullable: null
        |---
        |
        |# Demo
        |
        |[contentMetadata](std/content.ssc)
        |
        |```scala
        |List(
        |  contentMetadata("defaultRenderer"),
        |  contentMetadata("theme.density"),
        |  contentMetadata("flags.showBeta"),
        |  contentMetadata("limits.retries"),
        |  contentMetadata("tags"),
        |  contentMetadata("nullable"),
        |  contentMetadata("missing"),
        |  contentMetadata("theme.density.extra"),
        |  contentMetadata("name")
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
        assert(values.size == 9)
        assert(contentString(values(0)) == Some("toolkit"))
        assert(contentString(values(1)) == Some("compact"))
        assert(contentBool(values(2)) == Some(true))
        assert(contentNum(values(3)) == Some(3.0))
        values(4) match
          case Value.OptionV(Value.InstanceV("ListV", fields)) =>
            val tags = fields("values") match
              case Value.ListV(items) => items.flatMap(value => contentString(Value.OptionV(value)))
              case other              => fail(s"expected metadata tags list, got $other")
            assert(tags == List("alpha", "beta"))
          case other => fail(s"expected Some(ContentValue.ListV), got $other")
        assert(values(5) == Value.OptionV(Value.InstanceV("NullV", Map.empty)))
        assert(values(6) == Value.NoneV)
        assert(values(7) == Value.NoneV)
        assert(values(8) == Value.NoneV)
      case other =>
        fail(s"expected metadata lookup results, got $other")

  test("contentMetadata returns None without content frontmatter"):
    val source =
      """---
        |name: content-metadata-missing-root-test
        |---
        |
        |# Demo
        |
        |[contentMetadata](std/content.ssc)
        |
        |```scala
        |contentMetadata("defaultRenderer").isDefined.toString
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))
    assert(interp.lastResult == Value.StringV("false"))

  test("contentMetadata reports malformed paths"):
    val source =
      """---
        |name: content-metadata-malformed-path-test
        |content:
        |  defaultRenderer: toolkit
        |---
        |
        |# Demo
        |
        |[contentMetadata](std/content.ssc)
        |
        |```scala
        |contentMetadata("theme..density")
        |```
        |""".stripMargin

    val interp = Interpreter(
      out = java.io.PrintStream(java.io.ByteArrayOutputStream(), true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    val err = intercept[InterpretError]:
      interp.run(Parser.parse(source))
    assert(err.getMessage.contains("contentMetadata: path must be non-empty dot-separated segments"))

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

  test("contentToMarkdown renders selected Markdown content"):
    val source = os.read(repoRoot / "tests" / "conformance" / "content-to-markdown.ssc")
    val expected = os.read(repoRoot / "tests" / "conformance" / "expected" / "content-to-markdown.txt").stripTrailing
    val buf = java.io.ByteArrayOutputStream()
    val interp = Interpreter(
      out = java.io.PrintStream(buf, true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(Parser.parse(source))

    assert(buf.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing == expected)

  test("contentToMarkdown runs from content snapshot restored out of .sscc"):
    val source =
      """|---
         |name: sscc-content-runtime
         |content:
         |  defaultRenderer: toolkit
         |---
         |
         |# Artifact {#artifact route=/artifact}
         |
         |Intro **copy**.
         |
         |```yaml @id=artifact-data
         |value: 7
         |```
         |
         |[contentDocument, contentToMarkdown](std/content.ssc)
         |
         |```scala
         |println(contentToMarkdown(contentDocument()))
         |```
         |""".stripMargin

    val restored =
      SsccFormat.read(SsccFormat.write(Parser.parse(source))) match
        case Right(module) => module
        case Left(err)     => fail(s".sscc read failed: $err")
    assert(restored.document.nonEmpty)

    val buf = java.io.ByteArrayOutputStream()
    val interp = Interpreter(
      out = java.io.PrintStream(buf, true),
      baseDir = Some(repoRoot)
    )
    interp.installPlugins(List(ContentInterpreterPlugin()))
    interp.run(restored)

    val rendered = buf.toString(java.nio.charset.StandardCharsets.UTF_8)
    assert(rendered.contains("# Artifact {#artifact route=/artifact}"))
    assert(rendered.contains("Intro **copy**."))
    assert(rendered.contains("```yaml @id=artifact-data"))

  private def contentString(value: Value): Option[String] =
    value match
      case Value.OptionV(Value.InstanceV("Str", fields)) =>
        fields.get("value").collect { case Value.StringV(v) => v }
      case _ => None

  private def instanceFields(value: Value, typeName: String): Map[String, Value] =
    value match
      case inst: Value.InstanceV if inst.typeName == typeName => inst.effectiveFields
      case other => fail(s"expected $typeName, got $other")

  private def listField(fields: Map[String, Value], name: String, context: String): List[Value] =
    fields.get(name) match
      case Some(Value.ListV(values)) => values
      case Some(other) => fail(s"expected $context list, got $other")
      case None => fail(s"missing $context")

  private def contentBool(value: Value): Option[Boolean] =
    value match
      case Value.OptionV(Value.InstanceV("Bool", fields)) =>
        fields.get("value").collect { case Value.BoolV(v) => v }
      case _ => None

  private def contentNum(value: Value): Option[Double] =
    value match
      case Value.OptionV(Value.InstanceV("Num", fields)) =>
        fields.get("value").collect { case Value.DoubleV(v) => v }
      case _ => None
