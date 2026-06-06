package scalascript

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{InterpretError, Interpreter, Value}
import scalascript.parser.Parser

class MarkdownContentFrontendSmokeTest extends AnyFunSuite:

  test("contentView lowers the current Markdown document and emit writes frontend assets"):
    val outDir = Files.createTempDirectory("ssc-markdown-content-frontend")
    val src =
      s"""---
         |name: markdown-content-frontend-smoke
         |frontend: react
         |---
         |
         |# Pricing {#pricing route=/pricing}
         |
         |Intro with [docs](/docs).
         |
         |![Hero image](/hero.png "Hero")
         |
         |## Plans
         |
         |- Starter
         |- Pro
         |
         |[contentDocument](std/content.ssc)
         |
         |[contentView](std/ui/content.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |emit(contentView(contentDocument()), "${outDir.toString}")
         |println("markdown-content:ok")
         |```
         |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    ps.flush()

    assert(buf.toString.contains("markdown-content:ok"))
    assert(Files.exists(outDir.resolve("index.html")))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("Pricing"))
    assert(js.contains("Intro with "))
    assert(js.contains("/docs"))
    assert(js.contains("/hero.png"))
    assert(js.contains("Starter"))
    assert(js.contains("Pro"))

  test("contentToolkitBlock and contentToolkitSection compose selected Markdown content through the std/ui toolkit"):
    val outDir = Files.createTempDirectory("ssc-markdown-content-toolkit")
    val src =
      s"""---
         |name: markdown-content-toolkit-smoke
         |frontend: react
         |content:
         |  defaultRenderer: toolkit
         |---
         |
         |# Pricing {#pricing route=/pricing}
         |
         |Intro with [docs](/docs).
         |
         |## Plans
         |
         |- Starter
         |- Pro
         |
         |## Controls
         |
         |```yaml @id=team-controls @ui=toolkit
         |signals:
         |  teamName: "ScalaScript team"
         |  enabled: false
         |  applied: false
         |controls:
         |  type: card
         |  children:
         |    - type: heading
         |      level: 2
         |      text: Toolkit controls
         |    - type: textField
         |      signal: teamName
         |      label: Team name
         |    - type: checkbox
         |      signal: enabled
         |      label: Enable toolkit renderer
         |    - type: button
         |      signal: applied
         |      value: true
         |      label: Apply toolkit
         |      enabledWhen: enabled
         |    - type: show
         |      signal: applied
         |      then:
         |        type: hstack
         |        gap: 8
         |        children:
         |          - type: badge
         |            text: active
         |            variant: success
         |          - type: rawText
         |            text: "Preview for "
         |          - type: signalText
         |            signal: teamName
         |```
         |
         |```yaml @id=review-status @ui=toolkit
         |signals:
         |  reviewed: false
         |controls:
         |  type: card
         |  children:
         |    - type: heading
         |      level: 2
         |      text: Review status
         |    - type: checkbox
         |      signal: reviewed
         |      label: Reviewed on phone
         |    - type: show
         |      signal: reviewed
         |      then:
         |        type: badge
         |        text: ready
         |        variant: success
         |      else:
         |        type: badge
         |        text: pending
         |        variant: warning
         |```
         |
         |```yaml @id=unused-controls @ui=toolkit
         |controls:
         |  type: text
         |  text: Unused controls
         |```
         |
         |[contentToolkitBlock, contentToolkitSection](std/ui/content.ssc)
         |
         |[vstack](std/ui/layout.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |emit(
         |  lower(
         |    vstack(gap = 16)(
         |      contentToolkitSection("plans"),
         |      contentToolkitBlock("team-controls"),
         |      contentToolkitBlock("review-status")
         |    ),
         |    defaultTheme
         |  ),
         |  "${outDir.toString}"
         |)
         |println("markdown-content-toolkit:ok")
         |```
         |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    ps.flush()

    assert(buf.toString.contains("markdown-content-toolkit:ok"))
    assert(Files.exists(outDir.resolve("index.html")))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("Plans"))
    assert(js.contains("Starter"))
    assert(js.contains("Pro"))
    assert(js.contains("Toolkit controls"))
    assert(js.contains("Team name"))
    assert(js.contains("Enable toolkit renderer"))
    assert(js.contains("Apply toolkit"))
    assert(js.contains("Review status"))
    assert(js.contains("Reviewed on phone"))
    assert(js.contains("pending"))
    assert(!js.contains("Unused controls"))
    assert(js.contains("h('input'"))
    assert(js.contains("h('button'"))
    assert(js.contains("checkbox"))
    assert(js.contains("display: 'flex'"))
    assert(js.contains("flexDirection: 'column'"))
    assert(js.contains("fontSize: '24px'"))

  test("markdown toolkit links emit real frontend controls without YAML"):
    val outDir = Files.createTempDirectory("ssc-markdown-toolkit-links")
    val src =
      s"""---
         |name: markdown-toolkit-links-smoke
         |frontend: react
         |---
         |
         |# Markdown Toolkit Links
         |
         |## Controls {#controls}
         |
         |<!-- @meta id=markdown-controls -->
         |- [Team name](toolkit:textField?signal=teamName&value=ScalaScript%20team)
         |- [Enable preview](toolkit:checkbox?signal=enabled&value=false)
         |- [Status](toolkit:badge?text=Status&variant=default)
         |- [Current status](toolkit:signalText?signal=applyStatus&value=Not%20applied%20yet)
         |- [Apply](toolkit:button?signal=applyStatus&value=Applied%20from%20Markdown&enabledWhen=enabled)
         |- [ready](toolkit:badge?variant=success)
         |
         |[contentToolkitSection](std/ui/content.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |emit(lower(contentToolkitSection("controls"), defaultTheme), "${outDir.toString}")
         |println("markdown-toolkit-links:ok")
         |```
         |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    ps.flush()

    assert(buf.toString.contains("markdown-toolkit-links:ok"))
    assert(Files.exists(outDir.resolve("index.html")))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("Controls"))
    assert(js.contains("Team name"))
    assert(js.contains("Enable preview"))
    assert(js.contains("Apply"))
    assert(js.contains("Not applied yet"))
    assert(js.contains("Applied from Markdown"))
    assert(js.contains("ready"))
    assert(js.contains("h('input'"))
    assert(js.contains("h('button'"))
    assert(js.contains("checkbox"))

  test("contentToolkitBlock reports missing and duplicate selected block ids"):
    val missingSrc =
      """---
        |name: markdown-content-missing-block
        |frontend: react
        |---
        |
        |# Demo
        |
        |[contentToolkitBlock](std/ui/content.ssc)
        |
        |```scala
        |contentToolkitBlock("missing")
        |```
        |""".stripMargin

    val missingInterp = Interpreter(headless = true, baseDir = Some(TestPaths.repoRoot))
    missingInterp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    val missing = intercept[InterpretError]:
      missingInterp.run(Parser.parse(missingSrc))
    assert(missing.getMessage.contains("contentToolkitBlock: no block with id 'missing'"))

    val duplicateSrc =
      """---
        |name: markdown-content-duplicate-block
        |frontend: react
        |---
        |
        |# Demo
        |
        |```yaml @id=same @ui=toolkit
        |controls:
        |  type: text
        |  text: First
        |```
        |
        |```yaml @id=same @ui=toolkit
        |controls:
        |  type: text
        |  text: Second
        |```
        |
        |[contentToolkitBlock](std/ui/content.ssc)
        |
        |```scala
        |contentToolkitBlock("same")
        |```
        |""".stripMargin

    val duplicateInterp = Interpreter(headless = true, baseDir = Some(TestPaths.repoRoot))
    duplicateInterp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    val duplicate = intercept[InterpretError]:
      duplicateInterp.run(Parser.parse(duplicateSrc))
    assert(duplicate.getMessage.contains("contentToolkitBlock: duplicate block id 'same'"))

  test("content toolkit component registry replaces matching sections and blocks"):
    val outDir = Files.createTempDirectory("ssc-markdown-content-components")
    val src =
      s"""---
         |name: markdown-content-component-registry
         |frontend: react
         |---
         |
         |# Pricing
         |
         |<!-- @meta component=PlanList source=plans -->
         |## Plans
         |
         |- Starter
         |- Pro
         |
         |```yaml @id=stats @component=StatsBox
         |count: 2
         |```
         |
         |<!-- @meta component=MissingComponent -->
         |## Notes
         |
         |Fallback note
         |
         |[contentComponent, contentToolkitBlock, contentToolkitSection, contentToolkitOptionsWithComponents](std/ui/content.ssc)
         |
         |[vstack](std/ui/layout.ssc)
         |
         |[heading](std/ui/typography.ssc)
         |
         |[rawText](std/ui/reactive.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |val planList = contentComponent("PlanList") { ctx =>
         |  vstack(gap = 4)(
         |    heading(2, "Custom " + ctx.name),
         |    rawText(ctx.kind + ":" + ctx.id)
         |  )
         |}
         |
         |val statsBox = contentComponent("StatsBox") { ctx =>
         |  vstack(gap = 4)(
         |    heading(3, "Stats " + ctx.name),
         |    rawText(ctx.kind + ":" + ctx.id)
         |  )
         |}
         |
         |val options = contentToolkitOptionsWithComponents([planList, statsBox])
         |emit(
         |  lower(
         |    vstack(gap = 16)(
         |      contentToolkitSection("plans", options),
         |      contentToolkitBlock("stats", options),
         |      contentToolkitSection("notes", options)
         |    ),
         |    defaultTheme
         |  ),
         |  "${outDir.toString}"
         |)
         |println("markdown-content-components:ok")
         |```
         |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    ps.flush()

    assert(buf.toString.contains("markdown-content-components:ok"))
    assert(Files.exists(outDir.resolve("index.html")))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("Custom PlanList"))
    assert(js.contains("section:plans"))
    assert(js.contains("Stats StatsBox"))
    assert(js.contains("block:stats"))
    assert(js.contains("Notes"))
    assert(js.contains("Fallback note"))
    assert(!js.contains("- Starter"))
    assert(!js.contains("- Pro"))

  test("content toolkit components receive data references from Markdown metadata"):
    val outDir = Files.createTempDirectory("ssc-markdown-content-data-binding")
    val src =
      s"""---
         |name: markdown-content-data-binding
         |frontend: react
         |---
         |
         |# Pricing
         |
         |```yaml @id=plans-data
         |plans:
         |  - id: starter
         |    title: Starter
         |  - id: pro
         |    title: Pro
         |```
         |
         |<!-- @meta component=PlanList data=plans-data -->
         |## Plans
         |
         |- Default starter
         |- Default pro
         |
         |```yaml @id=inline-stats @component=StatsBox
         |count: 2
         |```
         |
         |<!-- @meta component=PlanSummary data=plans-data id=plan-summary -->
         |Plan summary paragraph.
         |
         |<!-- @meta component=MissingData data=missing-data -->
         |## Missing Data
         |
         |Fallback data state
         |
         |[ContentValue, contentData](std/content.ssc)
         |
         |[contentComponent, contentToolkitBlock, contentToolkitSection, contentToolkitOptionsWithComponents](std/ui/content.ssc)
         |
         |[vstack](std/ui/layout.ssc)
         |
         |[heading](std/ui/typography.ssc)
         |
         |[rawText](std/ui/reactive.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |val dataState = (value: Option[ContentValue]) =>
         |  value match
         |    case Some(_) => "has-data"
         |    case None    => "no-data"
         |
         |val planList = contentComponent("PlanList") { ctx =>
         |  vstack(gap = 4)(
         |    heading(2, "Plan data"),
         |    rawText(dataState(ctx.data))
         |  )
         |}
         |
         |val statsBox = contentComponent("StatsBox") { ctx =>
         |  vstack(gap = 4)(
         |    heading(3, "Inline stats"),
         |    rawText(dataState(ctx.data))
         |  )
         |}
         |
         |val planSummary = contentComponent("PlanSummary") { ctx =>
         |  vstack(gap = 4)(
         |    heading(3, "Block data ref"),
         |    rawText(dataState(ctx.data))
         |  )
         |}
         |
         |val missingData = contentComponent("MissingData") { ctx =>
         |  vstack(gap = 4)(
         |    heading(3, "Missing data"),
         |    rawText(dataState(ctx.data))
         |  )
         |}
         |
         |val options = contentToolkitOptionsWithComponents([planList, statsBox, planSummary, missingData])
         |println("contentData=" + contentData("plans-data").isDefined.toString)
         |emit(
         |  lower(
         |    vstack(gap = 16)(
         |      contentToolkitSection("plans", options),
         |      contentToolkitBlock("inline-stats", options),
         |      contentToolkitBlock("plan-summary", options),
         |      contentToolkitSection("missing-data", options)
         |    ),
         |    defaultTheme
         |  ),
         |  "${outDir.toString}"
         |)
         |println("markdown-content-data-binding:ok")
         |```
         |""".stripMargin

    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val interp = Interpreter(out = ps, headless = true, baseDir = Some(TestPaths.repoRoot))
    interp.injectGlobal("_ssc_frontend_name", Value.StringV("react"))
    interp.run(Parser.parse(src))
    ps.flush()

    val stdout = buf.toString
    assert(stdout.contains("contentData=true"))
    assert(stdout.contains("markdown-content-data-binding:ok"))
    assert(Files.exists(outDir.resolve("index.html")))
    val js = Files.readString(outDir.resolve("app.js"))
    assert(js.contains("Plan data"))
    assert(js.contains("Inline stats"))
    assert(js.contains("Block data ref"))
    assert(js.contains("Missing data"))
    assert(js.contains("has-data"))
    assert(js.contains("no-data"))
    assert(!js.contains("- Default starter"))
