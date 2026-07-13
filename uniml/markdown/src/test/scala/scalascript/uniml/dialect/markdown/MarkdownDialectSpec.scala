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

  test("HTML block types use the correct end conditions") {
    // type 1 (pre/script/style/textarea) spans blank lines, ends at the close tag
    val pre = projectDoc("<pre>\na\n\nb\n</pre>\n")
    assert(pre.blocks.size == 1)
    pre.blocks.head match
      case MarkdownBlock.HtmlBlock(raw) => assert(raw.contains("a\n\nb") && raw.contains("</pre>"))
      case other => fail(s"expected one html block, got $other")
    // type 2 comment ends at the '-->' line; following text is a separate block
    projectDoc("<!-- a\nb -->\nafter\n").blocks match
      case Vector(MarkdownBlock.HtmlBlock(_), MarkdownBlock.Paragraph(is)) =>
        assert(is == Vector(MarkdownInline.Text("after")))
      case other => fail(s"expected html block then paragraph, got $other")
    // type 4 declaration is a single line
    assert(projectDoc("<!DOCTYPE html>\n\ntext\n").blocks.size == 2)
    // type 6 ends before a blank line
    projectDoc("<div>\nx\n</div>\n\nafter\n").blocks match
      case Vector(MarkdownBlock.HtmlBlock(_), MarkdownBlock.Paragraph(_)) => ()
      case other => fail(s"expected html block then paragraph, got $other")
    // type 7 (a bare custom tag) does NOT interrupt an open paragraph
    projectDoc("foo\n<x-widget>\nbar\n").blocks match
      case Vector(MarkdownBlock.Paragraph(_)) => ()
      case other => fail(s"expected a single paragraph, got $other")
  }

  test("deep and mixed container nesting projects the right structure") {
    // nested block quotes
    projectDoc("> > foo\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.BlockQuote(Vector(MarkdownBlock.Paragraph(_))))) => ()
      case other => fail(s"expected nested block quotes, got $other")
    // block quote containing a list
    projectDoc("> - a\n> - b\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.ListBlock(_, _, _, items))) => assert(items.size == 2)
      case other => fail(s"expected quote-of-list, got $other")
    // list item containing a block quote
    projectDoc("- > quote\n").blocks.head match
      case MarkdownBlock.ListBlock(_, _, _, items) =>
        assert(items.head.blocks.exists { case _: MarkdownBlock.BlockQuote => true; case _ => false })
      case other => fail(s"expected list-of-quote, got $other")
    // nested lists
    projectDoc("- - x\n").blocks.head match
      case MarkdownBlock.ListBlock(_, _, _, items) =>
        assert(items.head.blocks.exists { case _: MarkdownBlock.ListBlock => true; case _ => false })
      case other => fail(s"expected nested lists, got $other")
    // an empty '>' line separates two paragraphs within one block quote
    projectDoc("> foo\n>\n> bar\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.Paragraph(_), MarkdownBlock.Paragraph(_))) => ()
      case other => fail(s"expected one quote with two paragraphs, got $other")
  }

  test("continuation markers stay out of inline text; multi-line spans resolve across them") {
    // block-quote continuation: the '> ' marker is trivia, not paragraph text
    projectDoc("> a\n> b\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.Paragraph(is))) =>
        assert(is == Vector(MarkdownInline.Text("a"), MarkdownInline.SoftBreak, MarkdownInline.Text("b")))
      case other => fail(s"expected clean two-line quote paragraph, got $other")
    // emphasis that opens on one quoted line and closes on the next
    projectDoc("> *foo\n> bar*\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.Paragraph(Vector(MarkdownInline.Emphasis(children))))) =>
        assert(children == Vector(MarkdownInline.Text("foo"), MarkdownInline.SoftBreak, MarkdownInline.Text("bar")))
      case other => fail(s"expected multi-line emphasis across the '> ' marker, got $other")
    // list-item continuation: the indent is trivia; emphasis spans both lines
    projectDoc("- *x\n  y*\n").blocks.head match
      case MarkdownBlock.ListBlock(_, _, _, items) =>
        items.head.blocks.collectFirst { case MarkdownBlock.Paragraph(Vector(MarkdownInline.Emphasis(cs))) => cs } match
          case Some(cs) => assert(cs == Vector(MarkdownInline.Text("x"), MarkdownInline.SoftBreak, MarkdownInline.Text("y")))
          case None     => fail(s"expected emphasis in list item, got ${items.head.blocks}")
      case other => fail(s"expected list with continued emphasis, got $other")
  }

  test("lazy paragraph continuation keeps text inside its container") {
    // a marker-less line continues the block quote's paragraph
    projectDoc("> foo\nbar\n").blocks.head match
      case MarkdownBlock.BlockQuote(Vector(MarkdownBlock.Paragraph(inlines))) =>
        assert(inlines.contains(MarkdownInline.Text("foo")) && inlines.contains(MarkdownInline.Text("bar")))
      case other => fail(s"expected block quote with one continued paragraph, got $other")
    // a marker-less line continues a list item's paragraph
    projectDoc("- foo\nbar\n").blocks.head match
      case MarkdownBlock.ListBlock(_, _, _, items) =>
        val inlines = items.head.blocks.collectFirst { case MarkdownBlock.Paragraph(is) => is }.get
        assert(inlines.contains(MarkdownInline.Text("foo")) && inlines.contains(MarkdownInline.Text("bar")))
      case other => fail(s"expected list with continued item, got $other")
    // laziness stops at a blank line: quote then a separate top-level paragraph
    projectDoc("> foo\n\nbar\n").blocks match
      case Vector(MarkdownBlock.BlockQuote(_), MarkdownBlock.Paragraph(is)) =>
        assert(is == Vector(MarkdownInline.Text("bar")))
      case other => fail(s"expected quote then paragraph, got $other")
    // laziness stops at a block start (heading interrupts)
    projectDoc("> foo\n# bar\n").blocks match
      case Vector(MarkdownBlock.BlockQuote(_), MarkdownBlock.Heading(1, _, _)) => ()
      case other => fail(s"expected quote then heading, got $other")
  }

  test("tight vs loose list classification follows CommonMark") {
    def tightOf(text: String): Boolean =
      projectDoc(text).blocks.collectFirst { case l: MarkdownBlock.ListBlock => l.tight }.getOrElse(fail(s"no list in $text"))
    // tight: no blank lines between items
    assert(tightOf("- a\n- b\n"))
    assert(tightOf("1. x\n2. y\n"))
    // tight: a blank line merely trailing the final item
    assert(tightOf("- a\n- b\n\n"))
    // loose: items separated by a blank line
    assert(!tightOf("- a\n\n- b\n"))
    // loose: an item directly contains two blocks separated by a blank line
    assert(!tightOf("- a\n\n  second para\n"))
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

  test("HTML4/XHTML named entities decode to the correct characters") {
    val cases = Map(
      // Latin-1 block (generated) — spot-checks pin the ordering
      "&nbsp;" -> " ", "&copy;" -> "©", "&reg;" -> "®", "&deg;" -> "°",
      "&times;" -> "×", "&divide;" -> "÷", "&eacute;" -> "é", "&Ouml;" -> "Ö",
      "&szlig;" -> "ß", "&frac12;" -> "½", "&laquo;" -> "«", "&pound;" -> "£",
      // punctuation / symbols / arrows / math / Greek
      "&mdash;" -> "—", "&ndash;" -> "–", "&hellip;" -> "…", "&rarr;" -> "→",
      "&larr;" -> "←", "&euro;" -> "€", "&trade;" -> "™", "&bull;" -> "•",
      "&ldquo;" -> "“", "&rdquo;" -> "”", "&Alpha;" -> "Α", "&omega;" -> "ω",
      "&pi;" -> "π", "&Sigma;" -> "Σ", "&infin;" -> "∞", "&ne;" -> "≠",
      "&le;" -> "≤", "&ge;" -> "≥", "&sum;" -> "∑", "&radic;" -> "√",
      "&amp;" -> "&", "&lt;" -> "<",
    )
    cases.foreach { case (entity, expected) =>
      assert(paragraphInlines(entity) == Vector(MarkdownInline.Text(expected)), s"$entity should decode to $expected")
    }
    // unknown named entities stay literal (lossless); numeric references still decode
    assert(paragraphInlines("&foobar;") == Vector(MarkdownInline.Text("&foobar;")))
    assert(paragraphInlines("&#65;&#x41;") == Vector(MarkdownInline.Text("AA")))
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

  test("extended CommonMark/GFM edge-case corpus stays lossless and chunk-invariant") {
    // Edge cases across every family — the ones most likely to drop a character
    // or diverge on a chunk split. Losslessness + no-throw is the universal check.
    val edge = Vector(
      // whitespace / tabs / blank structure
      "\t\tfoo\n", "   \n\tcode\n", "foo  \n", "  \n  \n  \n", "\n\n\n",
      // ATX / setext headings
      "#\n", "####### seven\n", "## closed ##\n", "##no-space\n", "### with # inside ###\n",
      "foo\nbar\n===\n", "> quoted\n---\n", "Foo\n----\n",
      // thematic breaks vs setext vs list
      "___\n", " - - -\n", "***\n***\n", "- - - - -\n",
      // fenced / indented code
      "~~~~\n``` inside\n~~~~\n", "```\n```\n", "    \tindented\n", "```info string here\ncode\n```\n", "~~~ ~~~\n",
      // block quotes
      ">\n", ">foo\n", "> > > deep\n", ">     code in quote\n", "> a\n> \n> b\n",
      // lists
      "1) one\n2) two\n", "10. ten\n", "- \n- item\n", "+ a\n+ b\n", "*\tfoo\n", "- a\n\n- b\n",
      // emphasis edge cases (delimiter algorithm stress)
      "**foo*bar**\n", "*foo**bar*\n", "***foo***\n", "foo******bar\n", "a * b * c\n",
      "_ _ _\n", "**a *b* c**\n", "*(*foo*)*\n", "__underscore__\n", "a_b_c\n",
      // code spans
      "`` foo ` bar ``\n", "`foo``bar`\n", "``\n``\n", "` ` `\n", "`unclosed\n",
      // links / images / autolinks
      "[foo](<my url>)\n", "[foo](/u (paren title))\n", "[]()\n", "[a][b]\n\n[b]: /u\n",
      "[foo](/url \"ti\\\"tle\")\n", "[[nested]](/u)\n", "![](/img)\n", "<>\n", "<http://>\n", "<a+b-c.d:x>\n",
      // raw HTML + entities
      "<a href=\"x\" data-y='z'>\n", "<!-- c --> text\n", "<?pi?> x\n", "</div>\n",
      "&nbsp; &notreal; &#; &#x; &#99999999;\n", "AT&T and R&amp;D\n",
      // hard/soft breaks and backslashes
      "foo\\\nbar\n", "foo\\\n", "\\\n", "a\\\\b\n", "text\\",
      // unicode / surrogates / combining
      "café ☕ 𝕏 👨‍👩‍👧 ́combining\n", "emoji: 😀\r\nnext\r\n",
    )
    edge.foreach { ex =>
      Vector(MarkdownProfile.CommonMark, MarkdownProfile.Gfm).foreach { profile =>
        val result = Markdown.parse(SourceInput.fromString(source, ex), profile)
        val recon = allTokens(result).map(_.lexeme).mkString
        assert(recon == ex, s"[$profile] lossless failed for ${ex.replace("\n", "\\n")}")
        Markdown.project(result, profile) // must not throw
        // chunk-split invariance on a representative split (midpoint)
        if ex.length > 1 then
          val mid = ex.length / 2
          val chunked = Markdown.parse(
            SourceInput(source, Vector(SourceChunk(ex.substring(0, mid)), SourceChunk(ex.substring(mid)))), profile)
          assert(chunked == result, s"[$profile] chunk split changed parse of ${ex.replace("\n", "\\n")}")
      }
    }
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
