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
         |[contentToolkitNode](std/ui/content.ssc)
         |
         |[lower](std/ui/lower.ssc)
         |
         |[defaultTheme](std/ui/theme.ssc)
         |
         |[vstack, hstack](std/ui/layout.ssc)
         |
         |[heading](std/ui/typography.ssc)
         |
         |[card](std/ui/containers.ssc)
         |
         |[textField, checkbox, signalButton](std/ui/input.ssc)
         |
         |[showWhen, signalText_, fragment_, rawText](std/ui/reactive.ssc)
         |
         |[badge](std/ui/display.ssc)
         |
         |[signal, emit](std/ui/primitives.ssc)
         |
         |```scala
         |val teamName = signal("teamName", "ScalaScript team")
         |val enabled  = signal("enabled", false)
         |val applied  = signal("applied", false)
         |def applyButton(disabled: Boolean) =
         |  signalButton(applied, true, "Apply toolkit", disabled = disabled)
         |val controls = card(
         |  heading(2, "Toolkit controls"),
         |  textField(value = teamName, label = "Team name"),
         |  checkbox(checked = enabled, label = "Enable toolkit renderer"),
         |  showWhen(enabled, applyButton(false), applyButton(true)),
         |  showWhen(applied,
         |    hstack(gap = 8)(badge("active", "success"), rawText("Preview for "), signalText_(teamName)),
         |    fragment_()
         |  )
         |)
         |emit(lower(vstack(gap = 16)(heading(1, "Markdown + toolkit"), controls, contentToolkitNode()), defaultTheme), "${outDir.toString}")
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
