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

  test("CLI interpreter run can compose selected Markdown content with the std/ui toolkit"):
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
           |```scalascript
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
    finally
      os.remove.all(sandbox)
