package scalascript.uniml.dialect.markdown.corpus

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.*

final class MarkdownCorpusSpec extends AnyFunSuite:
  private val commonMarkDigest =
    "f636418b09346809aa605ee4d52c3e600bf0f057251b77c386e49fae67a184a3"
  private val gfmDigest =
    "56ec730753789fa2a39db08f0dbfe7b63c9eec3b612494ff3fb0f75fef1facdd"

  test("pinned CommonMark 0.31.2 and GFM 0.29 data is complete and authenticated") {
    MarkdownCorpusData.requireAuthenticated()
    assert(MarkdownCorpusData.commonMark.size == 652)
    assert(MarkdownCorpusData.commonMark.map(_.example) == (1 to 652).toVector)
    assert(MarkdownCorpusData.gfmEnabledExtensions.size == 23)
    assert(
      MarkdownCorpusData.gfmEnabledExtensions.groupMapReduce(_.extension)(_ => 1)(_ + _) ==
        Map("table" -> 8, "tasklist" -> 2, "strikethrough" -> 2, "autolink" -> 11)
    )
    assert(MarkdownCorpusData.all.map(_.id).distinct.size == 675)

    assert(MarkdownCorpusData.commonMarkCanonicalSha256 == commonMarkDigest)
    assert(MarkdownCorpusData.gfmCanonicalSha256 == gfmDigest)
    assert(MarkdownCorpusSha256.canonical(MarkdownCorpusData.commonMark) == commonMarkDigest)
    assert(MarkdownCorpusSha256.canonical(MarkdownCorpusData.gfmEnabledExtensions) == gfmDigest)
    assert(MarkdownCorpusData.baselineRows.size == 675 * 5)
    assert(
      MarkdownCorpusData.baselineRows.map(_.key).distinct.size ==
        MarkdownCorpusData.baselineRows.size
    )
  }

  test("authentication rejects truncated passing corpus and baseline rosters") {
    val truncatedCorpus = MarkdownCorpusData.corpusAuthenticationProblems(
      MarkdownCorpusData.commonMark.dropRight(1),
      MarkdownCorpusData.gfmEnabledExtensions,
      MarkdownCorpusData.commonMarkCanonicalSha256,
      MarkdownCorpusData.gfmCanonicalSha256,
    )
    assert(truncatedCorpus.exists(_.contains("CommonMark roster differs")))

    val wrongGfmExtension =
      MarkdownCorpusData.gfmEnabledExtensions.updated(
        0,
        MarkdownCorpusData.gfmEnabledExtensions.head.copy(extension = "autolink"),
      )
    val malformedGfm = MarkdownCorpusData.corpusAuthenticationProblems(
      MarkdownCorpusData.commonMark,
      wrongGfmExtension,
      MarkdownCorpusData.commonMarkCanonicalSha256,
      MarkdownCorpusData.gfmCanonicalSha256,
    )
    assert(malformedGfm.exists(_.contains("GFM enabled-extension roster differs")))

    val truncatedBaseline = MarkdownCorpusData.baselineAuthenticationProblems(
      MarkdownCorpusData.all,
      MarkdownCorpusData.baselineRows.dropRight(5),
      MarkdownCorpusData.baselineFullRowsSha256,
    )
    assert(truncatedBaseline.exists(_.contains("baseline roster differs")))
  }

  test("test-only renderer has deterministic CommonMark and GFM observables") {
    val document = MarkdownDocument(
      Vector(
        MarkdownBlock.Heading(
          2,
          Vector(MarkdownInline.Text("A & "), MarkdownInline.Emphasis(Vector(MarkdownInline.Text("B")))),
          setext = false,
        ),
        MarkdownBlock.ListBlock(
          ordered = false,
          start = None,
          tight = true,
          items = Vector(ListItem(
            Vector(MarkdownBlock.Paragraph(Vector(MarkdownInline.Text("done")))),
            task = Some(true),
          )),
        ),
        MarkdownBlock.Table(
          Vector(TableCell(Vector(MarkdownInline.Text("left")))),
          Vector(ColumnAlignment.Left),
          Vector(Vector(TableCell(Vector(MarkdownInline.Code("x<y"))))),
        ),
      ),
      Vector.empty,
    )
    val expected =
      """<h2>A &amp; <em>B</em></h2>
        |<ul>
        |<li><input checked="" disabled="" type="checkbox"> done</li>
        |</ul>
        |<table>
        |<thead>
        |<tr>
        |<th align="left">left</th>
        |</tr>
        |</thead>
        |<tbody>
        |<tr>
        |<td align="left"><code>x&lt;y</code></td>
        |</tr>
        |</tbody>
        |</table>
        |""".stripMargin
    assert(MarkdownCorpusRenderer.render(document) == expected)
    assert(MarkdownCorpusRenderer.render(document) == MarkdownCorpusRenderer.render(document))
  }

  test("test-only renderer is calibrated to cmark HTML boundary rules") {
    val document = MarkdownDocument(
      Vector(
        MarkdownBlock.Paragraph(Vector(
          MarkdownInline.Text("quote=\"; "),
          MarkdownInline.Code("<a \""),
          MarkdownInline.Text(" "),
          MarkdownInline.Link(
            Vector(MarkdownInline.Text("x")),
            "https://e/x[y]&q='v'",
            Some("line 1\n\"line 2\""),
          ),
          MarkdownInline.Text(" "),
          MarkdownInline.Image(
            Vector(
              MarkdownInline.Text("a"),
              MarkdownInline.SoftBreak,
              MarkdownInline.RawHtml("<b>"),
              MarkdownInline.Code("\""),
            ),
            "/i[j]'",
            None,
          ),
        )),
        MarkdownBlock.ListBlock(
          ordered = false,
          start = None,
          tight = false,
          items = Vector(ListItem(Vector.empty)),
        ),
        MarkdownBlock.Table(
          Vector(
            TableCell(Vector(MarkdownInline.Text("h1"))),
            TableCell(Vector(MarkdownInline.Text("h2"))),
          ),
          Vector(ColumnAlignment.Default, ColumnAlignment.Right),
          Vector(
            Vector(TableCell(Vector(MarkdownInline.Text("one")))),
            Vector(
              TableCell(Vector(MarkdownInline.Text("a"))),
              TableCell(Vector(MarkdownInline.Text("b"))),
              TableCell(Vector(MarkdownInline.Text("discarded"))),
            ),
          ),
        ),
      ),
      Vector.empty,
    )
    val expected =
      """<p>quote=&quot;; <code>&lt;a &quot;</code> <a href="https://e/x%5By%5D&amp;q=&#x27;v&#x27;" title="line 1
        |&quot;line 2&quot;">x</a> <img src="/i%5Bj%5D&#x27;" alt="a &lt;b&gt;&quot;" /></p>
        |<ul>
        |<li></li>
        |</ul>
        |<table>
        |<thead>
        |<tr>
        |<th>h1</th>
        |<th align="right">h2</th>
        |</tr>
        |</thead>
        |<tbody>
        |<tr>
        |<td>one</td>
        |<td align="right"></td>
        |</tr>
        |<tr>
        |<td>a</td>
        |<td align="right">b</td>
        |</tr>
        |</tbody>
        |</table>
        |""".stripMargin
    assert(MarkdownCorpusRenderer.render(document) == expected)
  }

  test("renderer-only regressions stay green on official CommonMark and GFM cases") {
    val commonMarkIds = Set(315, 343, 352, 359, 590, 603)
    val gfmIds = Set(204, 279, 280)
    val selected =
      MarkdownCorpusData.commonMark.filter(testCase => commonMarkIds(testCase.example)) ++
        MarkdownCorpusData.gfmEnabledExtensions.filter(testCase => gfmIds(testCase.example))
    val report = MarkdownCorpusRunner.census(selected)
    assert(report.totalCases == commonMarkIds.size + gfmIds.size)
    assert(report.nonPassAxes == 0, report.failureOutput)
  }

  test("all 675 official cases run every independent axis and publish per-section census") {
    val commonMark = MarkdownCorpusRunner.census(MarkdownCorpusData.commonMark)
    val gfm = MarkdownCorpusRunner.census(MarkdownCorpusData.gfmEnabledExtensions)
    println(commonMark.summary("COMMONMARK-0.31.2-CENSUS"))
    println(gfm.summary("GFM-0.29-ENABLED-CENSUS"))
    (commonMark.sectionLines ++ gfm.sectionLines).foreach(println)

    assert(commonMark.totalCases == 652)
    assert(gfm.totalCases == 23)
    assert(commonMark.totalAxes == commonMark.totalCases * 5)
    assert(gfm.totalAxes == gfm.totalCases * 5)
    assert(commonMark.rows.size == commonMark.totalAxes)
    assert(gfm.rows.size == gfm.totalAxes)
    assert(commonMark.nonPassRows.size == commonMark.nonPassAxes)
    assert(gfm.nonPassRows.size == gfm.nonPassAxes)

    val full = MarkdownCorpusReport(commonMark.comparisons ++ gfm.comparisons)
    assert(full.totalCases == 675)
    assert(full.passingCases == 607)
    assert(full.nonPassAxes == 75)
    assert(
      full.nonPassRows.groupMapReduce(_.axis)(_ => 1)(_ + _) ==
        Map("html" -> 68, "source" -> 3, "tokens" -> 3, "status" -> 1)
    )
    assert(full.rows == MarkdownCorpusData.baselineRows)
    assert(full.fullDigest == MarkdownCorpusData.baselineFullRowsSha256)
    assert(
      full.nonPassDigest ==
        "8a56fb44d56192a6984bd721ffdd1980f99958ee73cecc8a391e43891ed13f8b"
    )
    assert(
      full.sectionDigest ==
        "d39b8860e05a234977deeb5c3823115b89ce3f089d14f8a5f2834090a62905e4"
    )
  }

  test("iterative tree validator enforces source, containment, envelope, and origin semantics") {
    val source = SourceId("tree-invariant")
    val span = SourceSpan(
      source,
      SourcePosition.Start,
      SourcePosition(offset = 1, line = 1, column = 2),
    )
    val token = UniNode.Token(SourceToken(
      id = 0L,
      kind = "text",
      lexeme = "a",
      span = span,
      channel = TokenChannel.Syntax,
    ))
    val sourceBacked =
      UniNode.Branch("document", Vector(UniEdge(None, token)), span, Origin.SourceBacked)
    val synthetic =
      UniNode.Branch(
        "document",
        Vector(UniEdge(Some("body"), token)),
        span,
        Origin.Synthetic("unclosed:document"),
      )
    assert(MarkdownCorpusRunner.treeInvariantProblems(Vector(sourceBacked), source, "a").isEmpty)
    assert(MarkdownCorpusRunner.treeInvariantProblems(Vector(synthetic), source, "a").isEmpty)

    val wrongLexeme = UniNode.Token(SourceToken(0L, "text", "b", span))
    val wrongSpan = SourceSpan(source, SourcePosition.Start, SourcePosition.Start)
    val malformed =
      UniNode.Branch(
        "",
        Vector(UniEdge(None, wrongLexeme)),
        wrongSpan,
        Origin.Synthetic(""),
      )
    val problems =
      MarkdownCorpusRunner.treeInvariantProblems(Vector(malformed), source, "a")
    assert(problems.exists(_.contains("kind=<empty>")))
    assert(problems.exists(_.contains("synthetic-reason=<empty>")))
    assert(problems.exists(_.contains("outside-parent")))
    assert(problems.exists(_.contains("source-slice=a")))

    var deep: UniNode = token
    var depth = 0
    while depth < 2048 do
      deep = UniNode.Branch(
        s"depth-$depth",
        Vector(UniEdge(None, deep)),
        span,
        Origin.SourceBacked,
      )
      depth += 1
    assert(MarkdownCorpusRunner.treeInvariantProblems(Vector(deep), source, "a").isEmpty)
  }

  test("strict decision turns a mutated known-good expectation red with id and diff") {
    val knownGood = MarkdownCorpusData.commonMark.find(_.example == 652).get
    val green = MarkdownCorpusRunner.census(Vector(knownGood))
    assert(MarkdownCorpusGate.decide(green).passed)

    val mutated = knownGood.copy(html = knownGood.html + "<!-- mutation -->\n")
    val red = MarkdownCorpusGate.decide(MarkdownCorpusRunner.census(Vector(mutated)))
    assert(!red.passed)
    assert(red.output.contains(knownGood.id))
    assert(red.output.contains("axis=html"))
    assert(red.output.contains("--- expected"))
    assert(red.output.contains("+++ actual"))
    assert(red.output.contains("code-point offset"))
  }

  test("bounded chunk schedule targets CRLF and astral UTF-16 boundaries") {
    val source = "a\r\n😀z"
    val schedules = MarkdownCorpusRunner.chunkSchedules(source)
    def split(label: String): Vector[String] =
      schedules.find(_._1.split("\\+").contains(label)).map(_._2).get
    assert(schedules.size <= 12)
    assert(split("before-first-crlf") == Vector("a", "\r\n😀z"))
    assert(split("inside-first-crlf") == Vector("a\r", "\n😀z"))
    assert(split("after-first-crlf") == Vector("a\r\n", "😀z"))
    assert(split("before-first-surrogate") == Vector("a\r\n", "😀z"))
    assert(split("inside-first-surrogate") == Vector("a\r\n\uD83D", "\uDE00z"))
    assert(split("after-first-surrogate") == Vector("a\r\n😀", "z"))
  }
