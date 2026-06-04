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
