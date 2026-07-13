package scalascript.uniml.dialect.markdown.bridge

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.{ContentBlock, ContentInline, DocumentContent, EmbeddedKind, SectionContent}
import scalascript.parser.Parser
import scalascript.uniml.{SourceId, SourceInput}
import scalascript.uniml.dialect.markdown.{Markdown, MarkdownProfile}

final class MarkdownDocContentSpec extends AnyFunSuite:
  private val source = SourceId("memory:bridge.md")

  private def bridge(text: String, profile: MarkdownProfile = MarkdownProfile.CommonMark): MarkdownBridgeResult =
    val result = Markdown.parse(SourceInput.fromString(source, text), profile)
    val doc = Markdown.project(result, profile).document.getOrElse(fail(s"projection failed: ${result.diagnostics}"))
    MarkdownDocContent.bridge(doc)

  // ── structure ────────────────────────────────────────────────────────

  test("headings build a nested section tree; pre-heading blocks become document blocks") {
    val r = bridge("intro paragraph\n\n# Top\n\nunder top\n\n## Sub\n\nunder sub\n")
    val d = r.document
    assert(d.blocks.collect { case ContentBlock.Paragraph(is, _) => textOf(is) } == List("intro paragraph"))
    assert(d.sections.map(_.title) == List("Top"))
    val top = d.sections.head
    assert(top.level == 1)
    assert(top.blocks.collect { case ContentBlock.Paragraph(is, _) => textOf(is) } == List("under top"))
    assert(top.children.map(_.title) == List("Sub"))
    assert(top.children.head.blocks.collect { case ContentBlock.Paragraph(is, _) => textOf(is) } == List("under sub"))
    assert(d.title == Some("Top"))
  }

  test("inline emphasis/strong/code/link map to ContentInline") {
    val r = bridge("a *b* **c** `d` [e](/u)\n")
    val para = r.document.blocks.collectFirst { case p: ContentBlock.Paragraph => p }.get
    assert(para.inlines == List(
      ContentInline.Text("a "),
      ContentInline.Emphasis(List(ContentInline.Text("b"))),
      ContentInline.Text(" "),
      ContentInline.Strong(List(ContentInline.Text("c"))),
      ContentInline.Text(" "),
      ContentInline.Code("d"),
      ContentInline.Text(" "),
      ContentInline.Link(List(ContentInline.Text("e")), "/u", None),
    ))
  }

  test("a single-image paragraph becomes an Image block") {
    val r = bridge("![alt text](/img.png \"t\")\n")
    assert(r.document.blocks == List(ContentBlock.Image("/img.png", "alt text", Some("t"))))
  }

  test("bullet and ordered lists map to list blocks") {
    val bul = bridge("- one\n- two\n").document.blocks
    bul match
      case List(ContentBlock.BulletList(items, _)) => assert(items.size == 2)
      case other => fail(s"expected bullet list, got $other")
    val ord = bridge("3. a\n4. b\n").document.blocks
    ord match
      case List(ContentBlock.OrderedList(items, start, _)) => assert(items.size == 2 && start == 3)
      case other => fail(s"expected ordered list, got $other")
  }

  test("fenced code maps to an Embedded block") {
    val r = bridge("```json\n{\"x\":1}\n```\n")
    r.document.blocks match
      case List(ContentBlock.Embedded(lang, src, kind, _, _)) =>
        assert(lang == "json"); assert(src == "{\"x\":1}\n"); assert(kind == EmbeddedKind.StructuredData)
      case other => fail(s"expected embedded, got $other")
  }

  test("GFM table maps to a Table block with alignments") {
    val r = bridge("| a | b |\n| :- | -: |\n| 1 | 2 |\n", MarkdownProfile.Gfm)
    r.document.blocks match
      case List(ContentBlock.Table(headers, rows, aligns, _)) =>
        assert(headers.map(textOf) == List("a", "b"))
        assert(aligns == List("left", "right"))
        assert(rows.map(_.map(textOf)) == List(List("1", "2")))
      case other => fail(s"expected table, got $other")
  }

  // ── model-loss diagnostics ─────────────────────────────────────────────

  test("block quotes, thematic breaks, HTML blocks and definitions report model loss") {
    val r = bridge("> quote\n\n***\n\n<div>x</div>\n\n[a]: /u\n\nuse [a]\n")
    val codes = r.diagnostics.map(_.code).toSet
    assert(codes.contains("uniml.markdown.bridge.block-quote"))
    assert(codes.contains("uniml.markdown.bridge.thematic-break"))
    assert(codes.contains("uniml.markdown.bridge.html-block"))
    assert(codes.contains("uniml.markdown.bridge.link-definition"))
    // block-quote content is flattened, not dropped
    assert(r.document.blocks.exists { case ContentBlock.Paragraph(is, _) => textOf(is) == "quote"; case _ => false })
  }

  test("strikethrough, task items and inline images report loss but preserve content") {
    val r = bridge("- [x] ~~done~~ ![i](/i.png)\n", MarkdownProfile.Gfm)
    val codes = r.diagnostics.map(_.code).toSet
    assert(codes.contains("uniml.markdown.bridge.strikethrough"))
    assert(codes.contains("uniml.markdown.bridge.task-item"))
    assert(codes.contains("uniml.markdown.bridge.inline-image"))
  }

  test("hard/soft break distinction reports loss once") {
    val r = bridge("line one\nline two\n")
    assert(r.diagnostics.count(_.code == "uniml.markdown.bridge.line-break") == 1)
  }

  // ── differential against Parser.buildDocumentContent ─────────────────────

  // NOTE: the SSC parser treats a heading-less document as bare code ("код
  // целиком"); a heading (or fence) puts it in literate mode where prose/lists/
  // tables become DocumentContent blocks. The differential docs are all literate.
  test("bridge agrees with Parser.buildDocumentContent for representable documents") {
    val docs = Vector(
      "# Title\n\nA paragraph with *emphasis*, **strong**, `code` and a [link](/u).\n",
      "intro\n\n# One\n\ntext\n\n## Two\n\nmore text\n\n# Three\n\ndone\n",
      "# Lists\n\n- a\n- b\n- c\n\n1. x\n2. y\n",
      "# Img\n\n![alt](/img.png)\n\nafter image\n",
      "# Top\n\nSetext heading\n==============\n\nbody paragraph\n",
      "# Dup\n\n## Dup\n\n# Dup\n", // section id de-duplication
    )
    docs.foreach { text =>
      val reference = Parser.parse(text).document.getOrElse(fail(s"reference produced no document for $text"))
      val bridged = bridge(text).document
      assert(describe(bridged) == describe(reference), s"bridge differs from reference for ${text.replace("\n", "\\n")}")
    }
  }

  test("bridge agrees with the reference for a GFM table") {
    val text = "# Table\n\n| h1 | h2 |\n| --- | --- |\n| a | b |\n"
    val reference = Parser.parse(text).document.get
    val bridged = bridge(text, MarkdownProfile.Gfm).document
    assert(describe(bridged) == describe(reference))
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private def textOf(inlines: List[ContentInline]): String =
    inlines.map {
      case ContentInline.Text(v)       => v
      case ContentInline.Code(v)       => v
      case ContentInline.Emphasis(cs)  => textOf(cs)
      case ContentInline.Strong(cs)    => textOf(cs)
      case ContentInline.Link(l, _, _) => textOf(l)
      case ContentInline.Expr(s)       => s
    }.mkString

  /** A normalized structural view that ignores manifest/title/description/attrs,
    * section ids and embedded kind/data — everything both producers should agree
    * on for representable content. */
  private def describe(d: DocumentContent): String =
    val blocks = d.blocks.map(describeBlock).mkString(",")
    val sections = d.sections.map(describeSection).mkString(",")
    s"blocks[$blocks];sections[$sections]"

  private def describeSection(s: SectionContent): String =
    s"H${s.level}:${s.title}{${s.blocks.map(describeBlock).mkString(",")}}[${s.children.map(describeSection).mkString(",")}]"

  private def describeBlock(b: ContentBlock): String = b match
    case ContentBlock.Paragraph(is, _)        => s"P(${describeInlines(is)})"
    case ContentBlock.BulletList(items, _)    => s"UL(${items.map(_.map(describeBlock).mkString(",")).mkString("|")})"
    case ContentBlock.OrderedList(items, s, _) => s"OL$s(${items.map(_.map(describeBlock).mkString(",")).mkString("|")})"
    case ContentBlock.Image(src, alt, t, _)   => s"IMG($src,$alt,$t)"
    case ContentBlock.Table(h, r, a, _)       => s"TABLE(${h.map(describeInlines).mkString("|")};${r.map(_.map(describeInlines).mkString("|")).mkString("/")};${a.mkString(",")})"
    case ContentBlock.Embedded(lang, src, _, _, _) => s"CODE($lang:$src)"

  private def describeInlines(is: List[ContentInline]): String = is.map(describeInline).mkString

  private def describeInline(i: ContentInline): String = i match
    case ContentInline.Text(v)       => v
    case ContentInline.Emphasis(cs)  => s"*${describeInlines(cs)}*"
    case ContentInline.Strong(cs)    => s"**${describeInlines(cs)}**"
    case ContentInline.Code(v)       => s"`$v`"
    case ContentInline.Link(l, h, t) => s"[${describeInlines(l)}]($h,$t)"
    case ContentInline.Expr(s)       => s"$${$s}"
