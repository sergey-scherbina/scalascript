package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

class ContentNativeClientParityTest extends AnyFunSuite with Matchers:

  private lazy val repoRoot: os.Path =
    val cwd = os.pwd
    Iterator.iterate(cwd)(_ / os.up)
      .takeWhile(p => p != p / os.up)
      .find(p => os.exists(p / "build.sbt"))
      .getOrElse(cwd)

  test("markdown-native-controls example parses as one shared native-client source"):
    val src = os.read(repoRoot / "examples" / "frontend" / "markdown-native-controls" / "markdown-native-controls.ssc")
    val module = Parser.parse(src)
    module.document.flatMap(_.title) shouldBe Some("Native Controls From Markdown")

  test("JVM codegen exposes Markdown toolkit helpers for Swing native frontend"):
    val code = JvmGen.generate(Parser.parse(selectedSource), Some(repoRoot), frontendOverride = Some("swing"))
    code should include ("def contentToolkitBlock")
    code should include ("def contentToolkitSection")
    code should include ("def _ssc_tk_render_control")
    code should include ("case class _SscTkLink")
    code should include ("def _ssc_tk_markdown_block")
    code should include ("scalascript-frontend-swing")
    code should include ("scalascript-frontend-javafx")
    code should include ("object primitives")

  test("JVM codegen keeps std/ui primitives available before SwiftUI native emission"):
    val code = JvmGen.generate(Parser.parse(wholeDocumentSource), Some(repoRoot), frontendOverride = Some("swiftui"))
    code should include ("def contentToolkitNode")
    code should include ("def _ssc_tk_render_control")
    code should include ("scalascript-frontend-swiftui")
    code should include ("object primitives")
    code should include ("def _ssc_ui_emit_native_platform_to_dir")

  private def selectedSource: String =
    """|---
       |name: markdown-native-codegen-test
       |frontend: swing
       |---
       |
       |# Native Markdown Controls
       |
       |<!-- @meta component=TeamSummary data=team-data -->
       |## Team Summary {#team-summary}
       |
       |```yaml @id=team-data
       |team:
       |  name: "ScalaScript team"
       |```
       |
       |```yaml @id=native-controls @ui=toolkit
       |signals:
       |  teamName: "ScalaScript team"
       |  enabled: false
       |  applied: false
       |controls:
       |  type: card
       |  children:
       |    - type: textField
       |      signal: teamName
       |      label: Team name
       |    - type: checkbox
       |      signal: enabled
       |      label: Enable native renderer
       |    - type: button
       |      signal: applied
       |      value: true
       |      label: Apply native controls
       |```
       |
       |[contentData](std/content.ssc)
       |
       |[contentComponent, contentToolkitBlock, contentToolkitSection, contentToolkitOptionsWithComponents](std/ui/content.ssc)
       |
       |[vstack](std/ui/layout.ssc)
       |
       |[rawText](std/ui/reactive.ssc)
       |
       |[lower](std/ui/lower.ssc)
       |
       |[defaultTheme](std/ui/theme.ssc)
       |
       |[serve](std/ui/primitives.ssc)
       |
       |```scalascript
       |val summary = contentComponent("TeamSummary") { ctx =>
       |  rawText("data=" + ctx.data.isDefined.toString)
       |}
       |val options = contentToolkitOptionsWithComponents([summary])
       |val page = lower(vstack(gap = 12)(
       |  contentToolkitSection("team-summary", options),
       |  contentToolkitBlock("native-controls")
       |), defaultTheme)
       |println(contentData("team-data").isDefined.toString)
       |serve(page, 0)
       |```
       |""".stripMargin

  private def wholeDocumentSource: String =
    """|---
       |name: markdown-whole-native-codegen-test
       |frontend: swiftui
       |---
       |
       |# Whole Native Document
       |
       |Whole document marker
       |
       |```yaml @id=whole-controls @ui=toolkit
       |signals:
       |  wholeName: "Whole team"
       |  wholeEnabled: true
       |  wholeApplied: false
       |controls:
       |  type: card
       |  children:
       |    - type: textField
       |      signal: wholeName
       |      label: Whole field
       |    - type: checkbox
       |      signal: wholeEnabled
       |      label: Whole toggle
       |    - type: button
       |      signal: wholeApplied
       |      value: true
       |      label: Whole apply
       |```
       |
       |[contentToolkitNode](std/ui/content.ssc)
       |
       |[lower](std/ui/lower.ssc)
       |
       |[defaultTheme](std/ui/theme.ssc)
       |
       |[serve](std/ui/primitives.ssc)
       |
       |```scalascript
       |val page = lower(contentToolkitNode(), defaultTheme)
       |serve(page, 0)
       |```
       |""".stripMargin
