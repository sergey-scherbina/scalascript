package scalascript.uniml.dialect.markdown

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class MarkdownDialectSpec extends AnyFunSuite:
  private val source = SourceId("memory:test.md")

  private def parse(text: String, profile: MarkdownProfile = MarkdownProfile.CommonMark): ParseResult =
    Markdown.parse(SourceInput.fromString(source, text), profile)

  private def allTokens(result: ParseResult): Vector[SourceToken] =
    result.roots.flatMap(UniNode.sourceTokens)

  /** Concatenated token lexemes must exactly reconstruct the source, and ids
    * must be a dense monotonic 0..n-1 sequence. */
  private def assertLossless(text: String, profile: MarkdownProfile = MarkdownProfile.CommonMark): ParseResult =
    val result = parse(text, profile)
    val tokens = allTokens(result)
    assert(tokens.map(_.lexeme).mkString == text, s"lossless failed for ${text.replace("\n", "\\n")}")
    assert(tokens.map(_.id) == tokens.indices.map(_.toLong).toVector, s"non-monotonic ids for ${text.replace("\n", "\\n")}")
    result

  // ── losslessness ────────────────────────────────────────────────────────

  test("empty and whitespace input round-trip exactly") {
    Vector("", "\n", "\n\n\n", "   ", "  \n \n", "\r\n\r\n", "\r").foreach(assertLossless(_))
  }

  test("arbitrary unicode text round-trips exactly, CR/LF/CRLF distinct") {
    Vector(
      "hello world",
      "héllo 😀 wörld",
      "line1\nline2\r\nline3\rline4",
      "trailing spaces   \nnext",
      "# Heading\n\nParagraph with *emphasis* and `code`.\n",
    ).foreach(assertLossless(_))
  }

  test("all common block constructs are lossless") {
    val doc =
      """---
        |# ATX heading
        |
        |Setext
        |======
        |
        |A paragraph spanning
        |two lines.
        |
        |> a block quote
        |> second line
        |
        |- item one
        |- item two
        |
        |1. first
        |2. second
        |
        |```scala
        |val x = 1
        |```
        |
        |    indented code
        |
        |***
        |
        |[ref]: /url "title"
        |
        |<div>raw html block</div>
        |""".stripMargin
    assertLossless(doc)
  }

  test("all inline constructs are lossless") {
    Vector(
      "a *b* **c** ***d*** _e_ __f__",
      "text with `code span` and ``a ` b``",
      "a [link](/url \"t\") and ![img](/i.png)",
      "auto <https://example.com> and <a@b.com>",
      "escapes \\* \\` \\[ and entities &amp; &#65; &#x41;",
      "hard break  \nnext line and back\\\nslash break",
      "raw <span class=\"x\"> inline html",
    ).foreach(assertLossless(_))
  }

  // ── chunk invariance ──────────────────────────────────────────────────────

  test("parse is invariant across every chunk split, including CRLF and surrogates") {
    val texts = Vector(
      "# H1\n\ntext *em* `c`\n\n> quote\n\n- a\n- b\n",
      " CRLF\r\nlines\r\n\r\n> q\r\n",
      "emoji 😀 and 𝄞 surrogate pair in text\n",
      "```\nfenced\ncode\n```\ntrailing\n",
    )
    texts.foreach { text =>
      val baseline = parse(text)
      (0 to text.length).foreach { split =>
        val chunked = Markdown.parse(SourceInput(source, Vector(
          SourceChunk(text.substring(0, split)),
          SourceChunk(text.substring(split)),
        )))
        assert(chunked == baseline, s"chunk split $split changed the parse of ${text.replace("\n", "\\n")}")
      }
    }
  }

  // ── structure ────────────────────────────────────────────────────────────

  test("ATX headings build heading branches with the right level") {
    val result = assertLossless("### Title ###\n")
    val heading = firstBranch(result, MdBranch.Heading)
    assert(heading.nonEmpty)
    val doc = Markdown.project(result).document.get
    assert(doc.blocks == Vector(MarkdownBlock.Heading(3, Vector(MarkdownInline.Text("Title")), setext = false)))
  }

  test("setext heading projects level 1 and 2") {
    val doc1 = projectDoc("Title\n=====\n")
    assert(doc1.blocks.head == MarkdownBlock.Heading(1, Vector(MarkdownInline.Text("Title")), setext = true))
    val doc2 = projectDoc("Title\n-----\n")
    assert(doc2.blocks.head == MarkdownBlock.Heading(2, Vector(MarkdownInline.Text("Title")), setext = true))
  }

  test("thematic breaks and paragraphs") {
    val doc = projectDoc("para one\n\n***\n\npara two\n")
    assert(doc.blocks == Vector(
      MarkdownBlock.Paragraph(Vector(MarkdownInline.Text("para one"))),
      MarkdownBlock.ThematicBreak,
      MarkdownBlock.Paragraph(Vector(MarkdownInline.Text("para two"))),
    ))
  }

  test("fenced code preserves info string and literal") {
    val doc = projectDoc("```scala\nval x = 1\nval y = 2\n```\n")
    assert(doc.blocks == Vector(MarkdownBlock.CodeBlock(Some("scala"), "val x = 1\nval y = 2\n", fenced = true)))
  }

  test("block quote wraps inner blocks") {
    val doc = projectDoc("> hello\n")
    doc.blocks.head match
      case MarkdownBlock.BlockQuote(inner) =>
        assert(inner == Vector(MarkdownBlock.Paragraph(Vector(MarkdownInline.Text("hello")))))
      case other => fail(s"expected block quote, got $other")
  }

  test("bullet and ordered lists project items") {
    val bullet = projectDoc("- a\n- b\n")
    bullet.blocks.head match
      case MarkdownBlock.ListBlock(false, _, _, items) => assert(items.size == 2)
      case other => fail(s"expected bullet list, got $other")
    val ordered = projectDoc("1. a\n2. b\n")
    ordered.blocks.head match
      case MarkdownBlock.ListBlock(true, start, _, items) =>
        assert(items.size == 2); assert(start == Some(1L))
      case other => fail(s"expected ordered list, got $other")
  }

  test("link reference definitions are collected, not rendered as paragraphs") {
    val result = assertLossless("[foo]: /url \"title\"\n\nSee [foo].\n")
    val doc = Markdown.project(result).document.get
    assert(doc.references.exists(d => d.label == "foo" && d.destination == "/url" && d.title == Some("title")))
    // the definition is not a paragraph block
    assert(!doc.blocks.exists { case MarkdownBlock.Paragraph(is) => is.exists { case MarkdownInline.Text(t) => t.contains("/url"); case _ => false }; case _ => false })
  }

  // ── inline projection ─────────────────────────────────────────────────────

  test("emphasis and strong nest correctly") {
    val em = paragraphInlines("a *b* c")
    assert(em == Vector(MarkdownInline.Text("a "), MarkdownInline.Emphasis(Vector(MarkdownInline.Text("b"))), MarkdownInline.Text(" c")))
    val strong = paragraphInlines("**bold**")
    assert(strong == Vector(MarkdownInline.Strong(Vector(MarkdownInline.Text("bold")))))
  }

  test("code spans normalize surrounding spaces") {
    assert(paragraphInlines("`code`") == Vector(MarkdownInline.Code("code")))
    assert(paragraphInlines("`` `x` ``") == Vector(MarkdownInline.Code("`x`")))
  }

  test("inline links and images project destination and title") {
    assert(paragraphInlines("[t](/u \"ti\")") ==
      Vector(MarkdownInline.Link(Vector(MarkdownInline.Text("t")), "/u", Some("ti"))))
    assert(paragraphInlines("![alt](/i.png)") ==
      Vector(MarkdownInline.Image(Vector(MarkdownInline.Text("alt")), "/i.png", None)))
  }

  test("reference links resolve from definitions") {
    val result = parse("[t][foo]\n\n[foo]: /url\n")
    val doc = Markdown.project(result).document.get
    val para = doc.blocks.collectFirst { case p: MarkdownBlock.Paragraph => p }.get
    assert(para.inlines.exists { case MarkdownInline.Link(_, "/url", _) => true; case _ => false })
  }

  test("autolinks, escapes and entities project as inert inlines") {
    assert(paragraphInlines("<https://x.io>") == Vector(MarkdownInline.Autolink("https://x.io", "https://x.io")))
    assert(paragraphInlines("\\*not em\\*") == Vector(MarkdownInline.Text("*not em*")))
    assert(paragraphInlines("&amp;&#65;") == Vector(MarkdownInline.Text("&A")))
  }

  // ── GFM profile ────────────────────────────────────────────────────────

  test("GFM strikethrough only under the GFM profile") {
    assert(paragraphInlines("~~gone~~", MarkdownProfile.Gfm) ==
      Vector(MarkdownInline.Strikethrough(Vector(MarkdownInline.Text("gone")))))
    // under CommonMark, tildes are literal text
    assert(paragraphInlines("~~gone~~", MarkdownProfile.CommonMark).exists {
      case MarkdownInline.Text(t) => t.contains("~"); case _ => false
    })
  }

  test("GFM tables project header, alignments and rows") {
    val table = "| a | b |\n| :- | -: |\n| 1 | 2 |\n"
    val result = assertLossless(table, MarkdownProfile.Gfm)
    val doc = Markdown.project(result, MarkdownProfile.Gfm).document.get
    doc.blocks.head match
      case MarkdownBlock.Table(header, aligns, rows) =>
        assert(header.size == 2)
        assert(aligns == Vector(ColumnAlignment.Left, ColumnAlignment.Right))
        assert(rows.size == 1)
      case other => fail(s"expected table, got $other")
  }

  test("GFM task list items carry checked state") {
    val result = assertLossless("- [x] done\n- [ ] todo\n", MarkdownProfile.Gfm)
    val doc = Markdown.project(result, MarkdownProfile.Gfm).document.get
    doc.blocks.head match
      case MarkdownBlock.ListBlock(_, _, _, items) =>
        assert(items.map(_.task) == Vector(Some(true), Some(false)))
      case other => fail(s"expected task list, got $other")
  }

  // ── ScalaScript profile ─────────────────────────────────────────────────

  test("ScalaScript front matter and expressions") {
    val result = assertLossless("---\ntitle: t\n---\n\nHello ${name}!\n", MarkdownProfile.ScalaScript)
    val exprs = allTokens(result).filter(_.kind == MdKind.ExpressionContent)
    assert(exprs.map(_.lexeme) == Vector("name"))
    assert(allTokens(result).exists(_.kind == MdKind.FrontMatterFence))
  }

  // ── malformed / diagnostics ─────────────────────────────────────────────

  test("unterminated fence retains tokens and warns, never throws") {
    val result = assertLossless("```\nunclosed code\n")
    assert(result.diagnostics.exists(_.code == "uniml.markdown.unterminated-fence"))
  }

  test("malformed inline retains all source characters") {
    Vector("[unclosed link", "*unbalanced", "`unterminated code", "<not a tag", "text ]stray").foreach(assertLossless(_))
  }

  test("source code-point limit fails with a structured fatal diagnostic") {
    val tiny = MarkdownLimits(maxSourceCodePoints = 4)
    val result = Markdown.parse(SourceInput.fromString(source, "abcdefghij"), MarkdownProfile.CommonMark, tiny)
    assert(result.diagnostics.exists(_.code == "uniml.markdown.limit.source"))
  }

  // ── CommonMark 0.31.2 example corpus (curated) ───────────────────────────

  test("curated CommonMark 0.31.2 examples are lossless and never throw") {
    // Representative examples spanning every leaf/inline family. The lossless
    // CST is the universal guarantee; the count is recorded in the spec Results.
    val examples = Vector(
      "\tfoo\tbaz\t\tbim\n",
      "  foo\n\nbar\n",
      "# foo\n## foo\n### foo\n",
      "Foo *bar*\n=========\n",
      "    a simple\n      indented code block\n",
      "```\n<\n >\n```\n",
      "~~~\naaa\n~~~\n",
      "```ruby\ndef foo(x)\n  return 3\nend\n```\n",
      "<table><tr><td>\n<pre>\n</pre>\n</td></tr></table>\n",
      "[foo]: /url \"title\"\n\n[foo]\n",
      "> # Foo\n> bar\n> baz\n",
      "> bar\nbaz\n> foo\n",
      "- foo\n- bar\n+ baz\n",
      "1. foo\n2. bar\n3) baz\n",
      "- `one\n- two`\n",
      "`hi`lo`\n",
      "foo\\\nbar\n",
      "*foo bar*\n",
      "**foo bar**\n",
      "foo***bar***baz\n",
      "[link](/uri \"title\")\n",
      "![foo](/url \"title\")\n",
      "<http://foo.bar.baz>\n",
      "<foo@bar.example.com>\n",
      "foo `` ` `` bar\n",
      "&amp; &copy; &#35; &#1234; &#992; &#0;\n",
      "\\!\\\"\\#\\$\\%\\&\\'\\(\\)\\*\\+\\,\\-\\.\\/\n",
      "hello $.;'there\n",
      "* * *\n",
      "aaa\n***\n\naaa\n",
      "\\*not emphasized*\n",
      "`code span` and *em* mix\n",
      "> ```\n> aaa\n\nbbb\n",
      "- a\n  - b\n",
    )
    var projected = 0
    examples.foreach { ex =>
      val result = parse(ex)
      val recon = allTokens(result).map(_.lexeme).mkString
      assert(recon == ex, s"lossless failed for ${ex.replace("\n", "\\n")}")
      // projection must never throw and must return a document
      Markdown.project(result).document.foreach(_ => projected += 1)
    }
    assert(projected == examples.size, s"only $projected/${examples.size} projected")
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private def firstBranch(result: ParseResult, kind: String): Option[UniNode.Branch] =
    def find(node: UniNode): Option[UniNode.Branch] = node match
      case b @ UniNode.Branch(`kind`, _, _, _) => Some(b)
      case UniNode.Branch(_, edges, _, _)      => edges.iterator.flatMap(e => find(e.child)).nextOption()
      case _                                   => None
    result.roots.iterator.flatMap(find).nextOption()

  private def projectDoc(text: String, profile: MarkdownProfile = MarkdownProfile.CommonMark): MarkdownDocument =
    val result = assertLossless(text, profile)
    Markdown.project(result, profile).document.getOrElse(fail(s"projection failed: ${result.diagnostics}"))

  private def paragraphInlines(text: String, profile: MarkdownProfile = MarkdownProfile.CommonMark): Vector[MarkdownInline] =
    val doc = projectDoc(text + "\n", profile)
    doc.blocks.collectFirst { case MarkdownBlock.Paragraph(is) => is }.getOrElse(Vector.empty)
