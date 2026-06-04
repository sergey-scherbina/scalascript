package scalascript

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.{Interpreter, Value}
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

  test("contentToolkitNode composes Markdown content through the std/ui toolkit"):
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
         |```yaml @ui=toolkit
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
         |[contentToolkitNode](std/ui/content.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[emit](std/ui/primitives.ssc)
         |
         |```scala
         |emit(lower(contentToolkitNode(), defaultTheme), "${outDir.toString}")
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
    assert(js.contains("Pricing"))
    assert(js.contains("Intro with "))
    assert(js.contains("/docs"))
    assert(js.contains("Starter"))
    assert(js.contains("Pro"))
    assert(js.contains("Toolkit controls"))
    assert(js.contains("Team name"))
    assert(js.contains("Enable toolkit renderer"))
    assert(js.contains("Apply toolkit"))
    assert(js.contains("h('input'"))
    assert(js.contains("h('button'"))
    assert(js.contains("checkbox"))
    assert(js.contains("display: 'flex'"))
    assert(js.contains("flexDirection: 'column'"))
    assert(js.contains("fontSize: '32px'"))
