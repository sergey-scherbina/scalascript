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

  test("CLI interpreter run can compose Markdown content with the std/ui toolkit"):
    val sandbox = os.temp.dir(prefix = "ssc-markdown-content-toolkit-cli-")
    try
      val outDir = sandbox / "site"
      val source =
        s"""---
           |name: markdown-content-toolkit-cli
           |frontend: react
           |content:
           |  defaultRenderer: toolkit
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
           |[contentToolkitNode](std/ui/content.ssc)
           |
           |[lower](std/ui/lower.ssc)
           |
           |[defaultTheme](std/ui/theme.ssc)
           |
           |[vstack](std/ui/layout.ssc)
           |
           |[heading, text](std/ui/typography.ssc)
           |
           |[emit](std/ui/primitives.ssc)
           |
           |```scalascript
           |val tree = vstack(gap = 16)(
           |  heading(1, "Markdown + toolkit"),
           |  text("The Markdown document is now a regular TkNode subtree."),
           |  contentToolkitNode()
           |)
           |emit(lower(tree, defaultTheme), "${outDir.toString}")
           |```
           |""".stripMargin
      val file = sandbox / "content-toolkit.ssc"
      os.write(file, source)

      compileViaBackend("int", file, Map("frontendName" -> "react")) match
        case CompileResult.Executed(_, _, exit) =>
          assert(exit == 0)
        case other =>
          fail(s"expected interpreter execution, got ${other.getClass.getSimpleName}: $other")

      assert(os.exists(outDir / "index.html"))
      val js = os.read(outDir / "app.js")
      assert(js.contains("Markdown + toolkit"))
      assert(js.contains("Simple plans for small teams."))
      assert(js.contains("Starter"))
      assert(js.contains("Pro"))
      assert(js.contains("display: 'flex'"))
      assert(js.contains("flexDirection: 'column'"))
      assert(js.contains("fontSize: '32px'"))
    finally
      os.remove.all(sandbox)
