package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.CompileResult

class MarkdownContentFrontendCliTest extends AnyFunSuite:

  test("CLI interpreter run loads content plugin and emits Markdown frontend assets"):
    val sandbox = os.temp.dir(prefix = "ssc-markdown-content-cli-")
    try
      val outDir = sandbox / "site"
      val source =
        s"""---
           |name: markdown-content-cli
           |frontend: react
           |---
           |
           |# Pricing {#pricing route=/pricing}
           |
           |Simple plans for small teams.
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
           |```scalascript
           |emit(contentView(contentDocument()), "${outDir.toString}")
           |```
           |""".stripMargin
      val file = sandbox / "content.ssc"
      os.write(file, source)

      compileViaBackend("int", file, Map("frontendName" -> "react")) match
        case CompileResult.Executed(_, _, exit) =>
          assert(exit == 0)
        case other =>
          fail(s"expected interpreter execution, got ${other.getClass.getSimpleName}: $other")

      assert(os.exists(outDir / "index.html"))
      val js = os.read(outDir / "app.js")
      assert(js.contains("Pricing"))
      assert(js.contains("Simple plans for small teams."))
      assert(js.contains("Starter"))
      assert(js.contains("Pro"))
    finally
      os.remove.all(sandbox)
