package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.*

class MarkdownMultiLinkImportTest extends AnyFunSuite:

  private def module(source: String) = Parser.parse(source.stripMargin)

  private def imports(source: String): List[Content.Import] =
    module(source).sections.flatMap(_.content).collect {
      case imp: Content.Import => imp
    }

  test("single-link import paragraph keeps existing import lowering"):
    val imps = imports(
      """# Test
        |
        |[One](./one.ssc)
        |
        |```scalascript
        |val x = 1
        |```
        |"""
    )

    assert(imps.map(_.path) == List("./one.ssc"))
    assert(imps.head.bindings == List(ImportBinding("One")))

  test("multi-link import paragraph lowers links in source order"):
    val imps = imports(
      """# Test
        |
        |[Alpha](./alpha.ssc)  
        |[Beta as Bee](./beta.ssc)
        |[Http from Api](./api.ssc)
        |
        |```scalascript
        |val x = 1
        |```
        |"""
    )

    assert(imps.map(_.path) == List("./alpha.ssc", "./beta.ssc", "./api.ssc"))
    assert(imps(0).bindings == List(ImportBinding("Alpha")))
    assert(imps(1).bindings == List(ImportBinding("Beta", alias = Some("Bee"))))
    assert(imps(2).bindings == List(ImportBinding("Http", fromModule = Some("Api"))))

  test("multi-binding import link composes with a second import link"):
    val imps = imports(
      """# Test
        |
        |[A, B](./ab.ssc) [C](./c.ssc)
        |
        |```scalascript
        |val x = 1
        |```
        |"""
    )

    assert(imps.map(_.path) == List("./ab.ssc", "./c.ssc"))
    assert(imps.head.bindings == List(ImportBinding("A"), ImportBinding("B")))
    assert(imps(1).bindings == List(ImportBinding("C")))

  test("prose paragraph with links stays prose"):
    val mod = module(
      """# Test
        |
        |See [Alpha](./alpha.ssc) before using it.
        |
        |```scalascript
        |val x = 1
        |```
        |"""
    )

    assert(mod.sections.flatMap(_.content).collect { case _: Content.Import => () }.isEmpty)
    assert(mod.sections.head.content.collect { case Content.Prose(text, _) => text }.contains("See Alpha before using it."))

  test("internal links stay prose cross-references"):
    val mod = module(
      """# Test
        |
        |[Overview](#overview)
        |
        |```scalascript
        |val x = 1
        |```
        |"""
    )

    assert(mod.sections.flatMap(_.content).collect { case _: Content.Import => () }.isEmpty)
    assert(mod.sections.head.content.collect { case Content.Prose(text, _) => text }.contains("Overview"))

  test("content snapshot omits pure multi-link import paragraphs"):
    val doc = module(
      """# Test
        |
        |[Alpha](./alpha.ssc) [Beta](./beta.ssc)
        |
        |Visible paragraph.
        |"""
    ).document.getOrElse(fail("expected document content snapshot"))

    val paragraphs = doc.sections.head.blocks.collect { case p: ContentBlock.Paragraph => p }
    assert(paragraphs.length == 1)
    assert(paragraphs.head.inlines == List(ContentInline.Text("Visible paragraph.")))
