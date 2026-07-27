package scalascript.uniml.dialect.markdown.corpus

import scala.util.control.NonFatal
import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.*

private[corpus] enum MarkdownAxis:
  case Source, Tokens, Status, Html, Chunks

private[corpus] final case class MarkdownAxisResult(
    axis: MarkdownAxis,
    expected: String,
    actual: String,
    problem: String,
):
  def matched: Boolean = expected == actual
  def status: String =
    if matched then "MATCH"
    else if actual.startsWith("<exception:") || actual.startsWith("<error:") then "ERROR"
    else "DIFF"

  def detail(testCase: MarkdownCorpusCase, diagnostics: String): String =
    s"""|[$status] corpus=${testCase.corpus} version=${testCase.version} id=${testCase.id}
        |section=${testCase.section}
        |extension=${if testCase.extension.isEmpty then "<none>" else testCase.extension}
        |axis=${axis.toString.toLowerCase}
        |embedded.source=${MarkdownCorpusDiff.visible(testCase.markdown)}
        |diagnostics=${if diagnostics.isEmpty then "<none>" else diagnostics}
        |problem=$problem
        |expected=${MarkdownCorpusDiff.visible(expected)}
        |actual=${MarkdownCorpusDiff.visible(actual)}
        |${MarkdownCorpusDiff.diff(expected, actual)}
        |""".stripMargin

private[corpus] final case class MarkdownCaseComparison(
    testCase: MarkdownCorpusCase,
    axes: Vector[MarkdownAxisResult],
    diagnostics: String,
    diagnosticsSha256: String,
    treeSha256: String,
):
  def matched: Boolean = axes.forall(_.matched)
  def failures: Vector[MarkdownAxisResult] = axes.filterNot(_.matched)

private[corpus] final case class MarkdownBaselineRow(
    corpus: String,
    version: String,
    profile: String,
    example: Int,
    section: String,
    extension: String,
    axis: String,
    status: String,
    expectedSha256: String,
    actualSha256: String,
    diagnosticsSha256: String,
    treeSha256: String,
):
  def fields: Vector[String] =
    Vector(
      corpus,
      version,
      profile,
      example.toString,
      section,
      extension,
      axis,
      status,
      expectedSha256,
      actualSha256,
      diagnosticsSha256,
      treeSha256,
    )

  def tsv: String = fields.map(MarkdownCorpusDiff.visible).mkString("\t")

  def key: (String, String, Int, String) =
    (corpus, version, example, axis)

private[corpus] final case class MarkdownSectionTotal(
    corpus: String,
    version: String,
    section: String,
    cases: Int,
    passingCases: Int,
    nonPassAxes: Int,
):
  def fields: Vector[String] =
    Vector(corpus, version, section, cases.toString, passingCases.toString, nonPassAxes.toString)

  def tsv: String = fields.map(MarkdownCorpusDiff.visible).mkString("\t")

private[corpus] final case class MarkdownCorpusReport(comparisons: Vector[MarkdownCaseComparison]):
  val totalCases: Int = comparisons.size
  val passingCases: Int = comparisons.count(_.matched)
  val failingCases: Int = totalCases - passingCases
  val totalAxes: Int = comparisons.map(_.axes.size).sum
  val nonPassAxes: Int = comparisons.map(_.failures.size).sum

  /** Complete compare-first observable: one row for every case x axis,
    * including MATCH.  Characterising only failures makes a truncated passing
    * corpus indistinguishable from a complete one. */
  val rows: Vector[MarkdownBaselineRow] =
    comparisons
      .flatMap { comparison =>
        comparison.axes.map { axis =>
          MarkdownBaselineRow(
            comparison.testCase.corpus,
            comparison.testCase.version,
            comparison.testCase.profile,
            comparison.testCase.example,
            comparison.testCase.section,
            comparison.testCase.extension,
            axis.axis.toString.toLowerCase,
            axis.status,
            MarkdownCorpusSha256.ofUtf8(axis.expected),
            MarkdownCorpusSha256.ofUtf8(axis.actual),
            comparison.diagnosticsSha256,
            comparison.treeSha256,
          )
        }
      }
      .sortBy(row => (row.corpus, row.version, row.example, row.axis))

  val nonPassRows: Vector[MarkdownBaselineRow] =
    rows.filterNot(_.status == "MATCH")

  val fullDigest: String =
    MarkdownCorpusSha256.fields(rows.flatMap(_.fields))

  val nonPassDigest: String =
    MarkdownCorpusSha256.fields(nonPassRows.flatMap(_.fields))

  val sections: Vector[MarkdownSectionTotal] =
    comparisons
      .groupBy(c => (c.testCase.corpus, c.testCase.version, c.testCase.section))
      .toVector
      .map { case ((corpus, version, section), values) =>
        MarkdownSectionTotal(
          corpus,
          version,
          section,
          values.size,
          values.count(_.matched),
          values.map(_.failures.size).sum,
        )
      }
      .sortBy(total => (total.corpus, total.version, total.section))

  val sectionDigest: String =
    MarkdownCorpusSha256.fields(sections.flatMap(_.fields))

  def summary(label: String): String =
    s"$label CASES=$totalCases PASS=$passingCases FAIL=$failingCases " +
      s"AXES=$totalAxes NON_PASS_AXES=$nonPassAxes FULL_ROW_SHA256=$fullDigest " +
      s"NON_PASS_ROW_SHA256=$nonPassDigest " +
      s"SECTION_SHA256=$sectionDigest"

  def sectionLines: Vector[String] =
    sections.map { total =>
      s"SECTION corpus=${total.corpus} version=${total.version} section=${total.section} " +
        s"CASES=${total.cases} PASS=${total.passingCases} NON_PASS_AXES=${total.nonPassAxes}"
    }

  def failureOutput: String =
    comparisons
      .filterNot(_.matched)
      .flatMap(comparison =>
        comparison.failures.map(_.detail(comparison.testCase, comparison.diagnostics))
      )
      .mkString

private[corpus] object MarkdownCorpusRunner:
  private val expectedStatus = "parse=Complete;projection=document"

  def census(cases: Vector[MarkdownCorpusCase]): MarkdownCorpusReport =
    MarkdownCorpusData.requireAuthenticated()
    characterize(cases)

  /** Bootstrap-only path used to rewrite the checked-in complete baseline.
    * The immutable source corpora are still authenticated, but the old
    * baseline is deliberately not trusted while replacing itself. */
  private[corpus] def baselineCensus(): MarkdownCorpusReport =
    MarkdownCorpusData.requireCorporaAuthenticated()
    characterize(MarkdownCorpusData.all)

  private def characterize(cases: Vector[MarkdownCorpusCase]): MarkdownCorpusReport =
    MarkdownCorpusReport(cases.map(compare))

  private def compare(testCase: MarkdownCorpusCase): MarkdownCaseComparison =
    val profile =
      if testCase.profile == "gfm" then MarkdownProfile.Gfm
      else MarkdownProfile.CommonMark
    val sourceId = SourceId(s"corpus:${testCase.id}")
    val whole = parseAttempt(SourceInput.fromString(sourceId, testCase.markdown), profile)

    val (
      sourceAxis,
      tokenAxis,
      statusAxis,
      htmlAxis,
      diagnostics,
      diagnosticsSha256,
      treeSha256,
    ) = whole match
      case Left(problem) =>
        val error = s"<exception:$problem>"
        val exceptionFields = Vector("exception", problem)
        (
          MarkdownAxisResult(MarkdownAxis.Source, testCase.markdown, error, "whole-source parse threw"),
          MarkdownAxisResult(MarkdownAxis.Tokens, "tree-invariants=ok", error, "tree invariants could not run"),
          MarkdownAxisResult(MarkdownAxis.Status, expectedStatus, error, "projection could not run"),
          MarkdownAxisResult(MarkdownAxis.Html, testCase.html, error, "semantic renderer could not run"),
          error,
          MarkdownCorpusSha256.fields(exceptionFields),
          MarkdownCorpusSha256.fields(exceptionFields),
        )
      case Right(parsed) =>
        val tokens = iterativeTokens(parsed.roots)
        val reconstructed = tokens.iterator.map(_.lexeme).mkString
        val tokenProblems = treeInvariantProblems(parsed.roots, sourceId, testCase.markdown)
        val projection = projectionAttempt(parsed, profile)
        val actualStatus = projection match
          case Left(problem) => s"parse=${parsed.status};projection=<exception:$problem>"
          case Right(value) =>
            s"parse=${parsed.status};projection=${if value.document.nonEmpty then "document" else "none"}"
        val actualHtml = projection match
          case Left(problem) => s"<exception:$problem>"
          case Right(value) =>
            value.document match
              case None => "<error:no-semantic-document>"
              case Some(document) =>
                renderAttempt(document) match
                  case Left(problem) => s"<exception:$problem>"
                  case Right(html)   => html
        (
          MarkdownAxisResult(
            MarkdownAxis.Source,
            testCase.markdown,
            reconstructed,
            if reconstructed == testCase.markdown then "exact reconstruction" else "source differs",
          ),
          MarkdownAxisResult(
            MarkdownAxis.Tokens,
            "tree-invariants=ok",
            if tokenProblems.isEmpty then "tree-invariants=ok" else tokenProblems.mkString(" | "),
            if tokenProblems.isEmpty then "all token and branch invariants hold"
            else "tree invariant violation",
          ),
          MarkdownAxisResult(
            MarkdownAxis.Status,
            expectedStatus,
            actualStatus,
            if actualStatus == expectedStatus then "accepted without error" else "status/projection differs",
          ),
          MarkdownAxisResult(
            MarkdownAxis.Html,
            testCase.html,
            actualHtml,
            if actualHtml == testCase.html then "exact official HTML" else "semantic HTML differs",
          ),
          diagnosticText(parsed),
          MarkdownCorpusSha256.fields(diagnosticObservable(parsed)),
          MarkdownCorpusSha256.fields(treeObservable(parsed.roots)),
        )

    val chunkAxis = chunkInvariantAxis(testCase, sourceId, profile, whole)
    MarkdownCaseComparison(
      testCase,
      Vector(sourceAxis, tokenAxis, statusAxis, htmlAxis, chunkAxis),
      diagnostics,
      diagnosticsSha256,
      treeSha256,
    )

  private def parseAttempt(
      input: SourceInput,
      profile: MarkdownProfile,
  ): Either[String, ParseResult] =
    try Right(Markdown.parse(input, profile))
    catch case NonFatal(error) => Left(errorText(error))

  private def projectionAttempt(
      parsed: ParseResult,
      profile: MarkdownProfile,
  ): Either[String, MarkdownProjectionResult] =
    try Right(Markdown.project(parsed, profile))
    catch case NonFatal(error) => Left(errorText(error))

  private def renderAttempt(document: MarkdownDocument): Either[String, String] =
    try Right(MarkdownCorpusRenderer.render(document))
    catch case NonFatal(error) => Left(errorText(error))

  private def errorText(error: Throwable): String =
    s"${error.getClass.getName}:${Option(error.getMessage).getOrElse("")}"

  private def diagnosticText(result: ParseResult): String =
    if result.diagnostics.isEmpty then ""
    else
      result.diagnostics.zipWithIndex
        .map { case (diagnostic, index) =>
          val span = diagnostic.span match
            case None => "<none>"
            case Some(value) =>
              s"${value.source.value}:${position(value.start)}-${position(value.end)}"
          val dialect = diagnostic.dialect.getOrElse("<none>")
          val details =
            if diagnostic.details.isEmpty then "<none>"
            else diagnostic.details.map { case (key, value) => s"$key=$value" }.mkString(",")
          s"$index:${diagnostic.severity}:${diagnostic.code}:${diagnostic.message}:" +
            s"span=$span:dialect=$dialect:details=$details"
        }
        .mkString(" | ")

  /** Full deterministic diagnostics observable.  Length-prefix hashing is
    * supplied by [[MarkdownCorpusSha256.fields]], so embedded separators,
    * newlines and empty values remain unambiguous. */
  private def diagnosticObservable(result: ParseResult): Vector[String] =
    val fields = Vector.newBuilder[String]
    fields += "diagnostics"
    fields += result.diagnostics.size.toString
    result.diagnostics.zipWithIndex.foreach { case (diagnostic, index) =>
      fields += index.toString
      fields += diagnostic.severity.toString
      fields += diagnostic.code
      fields += diagnostic.message
      diagnostic.span match
        case None =>
          fields += "no-span"
        case Some(span) =>
          fields += "span"
          appendSpanFields(fields, span)
      diagnostic.dialect match
        case None =>
          fields += "no-dialect"
        case Some(dialect) =>
          fields += "dialect"
          fields += dialect
      fields += diagnostic.details.size.toString
      diagnostic.details.foreach { case (key, value) =>
        fields += key
        fields += value
      }
    }
    fields.result()

  /** Exact preorder tree observable: roots, edge roles/order, branch kind,
    * origin, every span coordinate, and every token id/kind/channel/lexeme.
    * The walk is iterative so adversarial depth cannot overflow the host stack. */
  private def treeObservable(roots: Vector[UniNode]): Vector[String] =
    val fields = Vector.newBuilder[String]
    fields += "roots"
    fields += roots.size.toString
    // edgeKind: 0=root, 1=edge without role, 2=edge with role.  Keep
    // None distinct from Some("") in the exact observable.
    var pending: List[(UniNode, String, Int, String)] = Nil
    var rootIndex = roots.size - 1
    while rootIndex >= 0 do
      pending = (roots(rootIndex), s"r$rootIndex", 0, "") :: pending
      rootIndex -= 1
    while pending.nonEmpty do
      val (node, path, edgeKind, role) = pending.head
      pending = pending.tail
      fields += path
      edgeKind match
        case 0 =>
          fields += "root"
        case 1 =>
          fields += "edge-no-role"
        case _ =>
          fields += "edge-role"
          fields += role
      node match
        case UniNode.Token(token) =>
          fields += "token"
          fields += token.id.toString
          fields += token.kind
          fields += token.channel.toString
          fields += token.lexeme
          appendSpanFields(fields, token.span)
        case UniNode.Branch(kind, edges, span, origin) =>
          fields += "branch"
          fields += kind
          origin match
            case Origin.SourceBacked =>
              fields += "source-backed"
            case Origin.Synthetic(reason) =>
              fields += "synthetic"
              fields += reason
          appendSpanFields(fields, span)
          fields += edges.size.toString
          var edgeIndex = edges.size - 1
          while edgeIndex >= 0 do
            val edge = edges(edgeIndex)
            edge.role match
              case None =>
                pending = (edge.child, s"$path/$edgeIndex", 1, "") :: pending
              case Some(value) =>
                pending = (edge.child, s"$path/$edgeIndex", 2, value) :: pending
            edgeIndex -= 1
    fields.result()

  private def parseObservable(result: ParseResult): Vector[String] =
    Vector("parse", result.status.toString) ++
      diagnosticObservable(result) ++
      treeObservable(result.roots)

  private def appendSpanFields(
      fields: scala.collection.mutable.Builder[String, Vector[String]],
      span: SourceSpan,
  ): Unit =
    fields += span.source.value
    fields += span.start.offset.toString
    fields += span.start.line.toString
    fields += span.start.column.toString
    fields += span.end.offset.toString
    fields += span.end.line.toString
    fields += span.end.column.toString

  /** Iterative ordered walk; unlike `Vector :+` accumulation this remains
    * linear for large trees. */
  private def iterativeTokens(roots: Vector[UniNode]): Vector[SourceToken] =
    val output = Vector.newBuilder[SourceToken]
    var pending: List[UniNode] = roots.toList
    while pending.nonEmpty do
      pending.head match
        case UniNode.Token(token) =>
          output += token
          pending = pending.tail
        case UniNode.Branch(_, edges, _, _) =>
          pending = pending.tail
          val iterator = edges.reverseIterator
          while iterator.hasNext do pending = iterator.next().child :: pending
    output.result()

  private final case class SourceCoordinates(
      positions: Vector[SourcePosition],
      utf16Offsets: Vector[Int],
  )

  private final case class PendingNode(
      node: UniNode,
      path: String,
      parentSpan: Option[SourceSpan],
  )

  /** Validates the full tree without recursion.  Source offsets in UniML are
    * Unicode-code-point offsets (not UTF-16 indexes), so source slicing uses a
    * precomputed portable offset map. */
  private[corpus] def treeInvariantProblems(
      roots: Vector[UniNode],
      source: SourceId,
      text: String,
  ): Vector[String] =
    val problems = Vector.newBuilder[String]
    val coordinates = sourceCoordinates(text)
    val maxOffset = coordinates.positions.size - 1

    def validatePosition(label: String, value: SourcePosition): Boolean =
      if value.offset < 0 || value.offset > maxOffset then
        problems += s"$label.offset=${value.offset},bounds=0..$maxOffset"
        false
      else
        val expected = coordinates.positions(value.offset)
        if value != expected then
          problems += s"$label=${position(value)},expected=${position(expected)}"
          false
        else true

    def validateSpan(label: String, span: SourceSpan): Boolean =
      var valid = true
      if span.source != source then
        problems += s"$label.source=${span.source.value},expected=${source.value}"
        valid = false
      val startValid = validatePosition(s"$label.start", span.start)
      val endValid = validatePosition(s"$label.end", span.end)
      if span.end.offset < span.start.offset then
        problems += s"$label.range=${span.start.offset}..${span.end.offset},end-before-start"
        valid = false
      valid && startValid && endValid

    def spanOf(node: UniNode): SourceSpan =
      node match
        case UniNode.Token(token)             => token.span
        case UniNode.Branch(_, _, span, _)   => span

    var pending: List[PendingNode] = Nil
    var rootIndex = roots.size - 1
    while rootIndex >= 0 do
      pending = PendingNode(roots(rootIndex), s"root[$rootIndex]", None) :: pending
      rootIndex -= 1

    var previousRootEnd: Option[Int] = None
    while pending.nonEmpty do
      val current = pending.head
      pending = pending.tail
      val currentSpan = spanOf(current.node)
      val currentSpanValid = validateSpan(s"${current.path}.span", currentSpan)
      current.parentSpan.foreach { parent =>
        if currentSpan.source != parent.source ||
            currentSpan.start.offset < parent.start.offset ||
            currentSpan.end.offset > parent.end.offset
        then
          problems +=
            s"${current.path}.span=${currentSpan.start.offset}..${currentSpan.end.offset}," +
              s"outside-parent=${parent.start.offset}..${parent.end.offset}"
      }
      if current.path.startsWith("root[") && !current.path.contains("/") then
        previousRootEnd.foreach { end =>
          if currentSpan.start.offset < end then
            problems += s"${current.path}.start=${currentSpan.start.offset},previous-root-end=$end"
        }
        previousRootEnd = Some(currentSpan.end.offset)

      current.node match
        case UniNode.Token(token) =>
          // SourceToken has no Origin field: the core model deliberately
          // represents every token as source-backed.  Exact source-id,
          // coordinate, bounds, and slice equality below are therefore its
          // complete origin contract; a synthetic token is unrepresentable.
          if token.kind.isEmpty then problems += s"${current.path}.kind=<empty>"
          if currentSpanValid then
            val startUtf16 = coordinates.utf16Offsets(token.span.start.offset)
            val endUtf16 = coordinates.utf16Offsets(token.span.end.offset)
            val claimed = text.substring(startUtf16, endUtf16)
            if claimed != token.lexeme then
              problems +=
                s"${current.path}.lexeme=${MarkdownCorpusDiff.visible(token.lexeme)}," +
                  s"source-slice=${MarkdownCorpusDiff.visible(claimed)}"
        case UniNode.Branch(kind, edges, span, origin) =>
          if kind.isEmpty then problems += s"${current.path}.kind=<empty>"
          if edges.isEmpty then problems += s"${current.path}.edges=<empty>"
          origin match
            case Origin.SourceBacked => ()
            case Origin.Synthetic(reason) =>
              if reason.trim.isEmpty then problems += s"${current.path}.synthetic-reason=<empty>"

          edges.headOption.foreach { first =>
            val firstSpan = spanOf(first.child)
            if span.start != firstSpan.start then
              problems +=
                s"${current.path}.start=${position(span.start)}," +
                  s"first-child-start=${position(firstSpan.start)}"
          }
          edges.lastOption.foreach { last =>
            val lastSpan = spanOf(last.child)
            if span.end != lastSpan.end then
              problems +=
                s"${current.path}.end=${position(span.end)}," +
                  s"last-child-end=${position(lastSpan.end)}"
          }

          var previousChildEnd: Option[Int] = None
          var edgeIndex = 0
          while edgeIndex < edges.size do
            val edge = edges(edgeIndex)
            val childSpan = spanOf(edge.child)
            previousChildEnd.foreach { end =>
              if childSpan.start.offset < end then
                problems +=
                  s"${current.path}/$edgeIndex.start=${childSpan.start.offset}," +
                    s"previous-child-end=$end"
            }
            previousChildEnd = Some(childSpan.end.offset)
            edgeIndex += 1

          var reverseIndex = edges.size - 1
          while reverseIndex >= 0 do
            val edge = edges(reverseIndex)
            pending =
              PendingNode(edge.child, s"${current.path}/$reverseIndex", Some(span)) :: pending
            reverseIndex -= 1

    val tokens = iterativeTokens(roots)
    var expectedPosition = SourcePosition.Start
    var index = 0
    while index < tokens.size do
      val token = tokens(index)
      if token.id != index.toLong then
        problems += s"id[$index]=${token.id},expected=$index"
      if token.span.source != source then
        problems += s"source[$index]=${token.span.source.value},expected=${source.value}"
      if token.span.start != expectedPosition then
        problems += s"start[$index]=${position(token.span.start)},expected=${position(expectedPosition)}"
      val expectedEnd = advance(expectedPosition, token.lexeme)
      if token.span.end != expectedEnd then
        problems += s"end[$index]=${position(token.span.end)},expected=${position(expectedEnd)}"
      val width = codePointCount(token.lexeme)
      val spanWidth = token.span.end.offset - token.span.start.offset
      if spanWidth != width then
        problems += s"width[$index]=$spanWidth,codePoints=$width"
      expectedPosition = expectedEnd
      index += 1
    val completeEnd = advance(SourcePosition.Start, text)
    if expectedPosition != completeEnd then
      problems += s"coverageEnd=${position(expectedPosition)},expected=${position(completeEnd)}"
    problems.result()

  private def sourceCoordinates(text: String): SourceCoordinates =
    val positions = Vector.newBuilder[SourcePosition]
    val utf16Offsets = Vector.newBuilder[Int]
    var position = SourcePosition.Start
    var utf16 = 0
    positions += position
    utf16Offsets += utf16
    while utf16 < text.length do
      val width = codePointWidth(text, utf16)
      val point = text.substring(utf16, utf16 + width)
      position = advance(position, point)
      utf16 += width
      positions += position
      utf16Offsets += utf16
    SourceCoordinates(positions.result(), utf16Offsets.result())

  private def position(value: SourcePosition): String =
    s"${value.offset}:${value.line}:${value.column}"

  private def advance(position: SourcePosition, value: String): SourcePosition =
    var index = 0
    var offset = position.offset
    var line = position.line
    var column = position.column
    while index < value.length do
      val width = codePointWidth(value, index)
      if value.charAt(index) == '\n' then
        line += 1
        column = 1
      else column += 1
      offset += 1
      index += width
    SourcePosition(offset, line, column)

  private def codePointCount(value: String): Int =
    var result = 0
    var index = 0
    while index < value.length do
      index += codePointWidth(value, index)
      result += 1
    result

  private def codePointWidth(value: String, index: Int): Int =
    val first = value.charAt(index)
    if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length then
      val second = value.charAt(index + 1)
      if second >= '\uDC00' && second <= '\uDFFF' then 2 else 1
    else 1

  private def chunkInvariantAxis(
      testCase: MarkdownCorpusCase,
      source: SourceId,
      profile: MarkdownProfile,
      whole: Either[String, ParseResult],
  ): MarkdownAxisResult =
    val schedules = chunkSchedules(testCase.markdown)
    val wholeObservable = whole match
      case Left(problem)  => Vector("exception", problem)
      case Right(result)  => parseObservable(result)
    val wholeDigest = MarkdownCorpusSha256.fields(wholeObservable)
    val expected = s"all=${schedules.size};whole=$wholeDigest;equal-to-whole"
    val differences = Vector.newBuilder[String]
    schedules.foreach { case (label, chunks) =>
      parseAttempt(SourceInput(source, chunks.map(SourceChunk.apply)), profile) match
        case Left(problem) =>
          val actualObservable = Vector("exception", problem)
          if actualObservable != wholeObservable then
            differences +=
              s"$label=diff:$wholeDigest:${MarkdownCorpusSha256.fields(actualObservable)}"
        case Right(actual) =>
          val actualObservable = parseObservable(actual)
          if actualObservable != wholeObservable then
            differences +=
              s"$label=diff:$wholeDigest:${MarkdownCorpusSha256.fields(actualObservable)}"
    }
    val found = differences.result()
    val actual = if found.isEmpty then expected else found.mkString(" | ")
    MarkdownAxisResult(
      MarkdownAxis.Chunks,
      expected,
      actual,
      if found.isEmpty then "all bounded chunk schedules equal whole-source parse"
      else "chunk schedule changed parse result",
    )

  /** Fixed bounded schedule executed for every case.  In addition to generic
    * edges and thirds, it splits immediately before/between/after the first
    * CRLF and UTF-16 surrogate pair when present.  At most twelve schedules run
    * per case; duplicates are removed deterministically. */
  private[corpus] def chunkSchedules(text: String): Vector[(String, Vector[String])] =
    val length = text.length
    val first = math.min(1, length)
    val middle = length / 2
    val last = math.max(0, length - 1)
    val third = length / 3
    val twoThirds = (length * 2) / 3
    val crlf = text.indexOf("\r\n") match
      case -1 => Vector.empty
      case index =>
        Vector(
          "before-first-crlf" -> index,
          "inside-first-crlf" -> (index + 1),
          "after-first-crlf" -> (index + 2),
        )
    val surrogate = firstSurrogatePair(text) match
      case None => Vector.empty
      case Some(index) =>
        Vector(
          "before-first-surrogate" -> index,
          "inside-first-surrogate" -> (index + 1),
          "after-first-surrogate" -> (index + 2),
        )
    val splitPoints =
      Vector(
        "empty-prefix" -> 0,
        "after-first" -> first,
        "midpoint" -> middle,
        "before-last" -> last,
        "empty-suffix" -> length,
      ) ++ crlf ++ surrogate
    val candidates = splitPoints.map { case (label, offset) =>
      label -> Vector(text.substring(0, offset), text.substring(offset))
    } :+
      "thirds" -> Vector(
        text.substring(0, third),
        text.substring(third, twoThirds),
        text.substring(twoThirds),
      )
    candidates.foldLeft(Vector.empty[(String, Vector[String])]) { (result, candidate) =>
      val duplicate = result.indexWhere(_._2 == candidate._2)
      if duplicate < 0 then result :+ candidate
      else
        val existing = result(duplicate)
        result.updated(duplicate, (existing._1 + "+" + candidate._1) -> existing._2)
    }

  private def firstSurrogatePair(text: String): Option[Int] =
    var result: Option[Int] = None
    var index = 0
    while result.isEmpty && index + 1 < text.length do
      val first = text.charAt(index)
      val second = text.charAt(index + 1)
      if first >= '\uD800' && first <= '\uDBFF' && second >= '\uDC00' && second <= '\uDFFF' then
        result = Some(index)
      else index += 1
    result

private[corpus] object MarkdownCorpusDiff:
  private final case class Mismatch(
      expectedUtf16: Int,
      actualUtf16: Int,
      codePointOffset: Int,
      line: Int,
      column: Int,
  )

  def firstMismatch(expected: String, actual: String): String =
    mismatch(expected, actual) match
      case None => "none"
      case Some(value) =>
        s"first mismatch at code-point offset=${value.codePointOffset} " +
          s"line=${value.line} column=${value.column}"

  def diff(expected: String, actual: String): String =
    mismatch(expected, actual) match
      case None => "diff: <identical>"
      case Some(value) =>
        val expectedLine = lineAt(expected, value.expectedUtf16)
        val actualLine = lineAt(actual, value.actualUtf16)
        s"""|diff:
            |--- expected
            |+++ actual
            |@@ line ${value.line} column ${value.column} code-point offset ${value.codePointOffset} @@
            |-${visible(expectedLine)}
            |+${visible(actualLine)}""".stripMargin

  def visible(value: String): String =
    val output = new StringBuilder
    value.foreach {
      case '\\' => output.append("\\\\")
      case '\n' => output.append("\\n")
      case '\r' => output.append("\\r")
      case '\t' => output.append("\\t")
      case c if c < ' ' || c == '\u007f' =>
        output.append(f"\\u${c.toInt}%04x")
      case c => output.append(c)
    }
    output.result()

  private def mismatch(expected: String, actual: String): Option[Mismatch] =
    var expectedIndex = 0
    var actualIndex = 0
    var codePointOffset = 0
    var line = 1
    var column = 1
    while expectedIndex < expected.length && actualIndex < actual.length do
      val expectedPoint = codePointAt(expected, expectedIndex)
      val actualPoint = codePointAt(actual, actualIndex)
      if expectedPoint != actualPoint then
        return Some(Mismatch(expectedIndex, actualIndex, codePointOffset, line, column))
      expectedIndex += codePointWidth(expected, expectedIndex)
      actualIndex += codePointWidth(actual, actualIndex)
      if expectedPoint == '\n'.toInt then
        line += 1
        column = 1
      else column += 1
      codePointOffset += 1
    if expectedIndex != expected.length || actualIndex != actual.length then
      Some(Mismatch(expectedIndex, actualIndex, codePointOffset, line, column))
    else None

  private def codePointAt(value: String, index: Int): Int =
    val first = value.charAt(index)
    val width = codePointWidth(value, index)
    if width == 1 then first.toInt
    else
      val second = value.charAt(index + 1)
      0x10000 + ((first.toInt - 0xD800) << 10) + (second.toInt - 0xDC00)

  private def codePointWidth(value: String, index: Int): Int =
    val first = value.charAt(index)
    if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length then
      val second = value.charAt(index + 1)
      if second >= '\uDC00' && second <= '\uDFFF' then 2 else 1
    else 1

  private def lineAt(value: String, offset: Int): String =
    val bounded = math.min(offset, value.length)
    val startIndex = value.lastIndexOf('\n', math.max(0, bounded - 1)) + 1
    val rawEnd = value.indexOf('\n', bounded)
    val endIndex = if rawEnd < 0 then value.length else rawEnd
    value.substring(startIndex, endIndex)

private[corpus] final case class MarkdownGateDecision(passed: Boolean, output: String)

/** Fail-closed release entrypoint.  Unlike the regular census it exits red on
  * any source, token, status, semantic, or chunk-invariance mismatch. */
object MarkdownCorpusGate:
  private[corpus] def decide(report: MarkdownCorpusReport): MarkdownGateDecision =
    val details = report.failureOutput
    val sections = report.sectionLines.mkString("", "\n", if report.sections.nonEmpty then "\n" else "")
    MarkdownGateDecision(
      report.nonPassAxes == 0,
      details + sections + report.summary("STRICT"),
    )

  def main(args: Array[String]): Unit =
    // Authenticate the complete unfiltered roster before applying any CLI
    // filter.  A passing shard can never mask missing/tampered generated data.
    MarkdownCorpusData.requireAuthenticated()
    val selection = select(args.toVector)
    if selection.isEmpty then
      throw new IllegalArgumentException("Markdown corpus selection is empty")
    val report = MarkdownCorpusRunner.census(selection)
    val decision = decide(report)
    println(decision.output)
    if !decision.passed then
      throw new AssertionError(
        s"Markdown conformance failed: ${report.nonPassAxes} non-pass axes " +
          s"across ${report.failingCases}/${report.totalCases} cases"
      )

  private def select(args: Vector[String]): Vector[MarkdownCorpusCase] =
    var corpus: Option[String] = None
    var example: Option[Int] = None
    var index = 0
    while index < args.length do
      args(index) match
        case "--commonmark" =>
          if corpus.nonEmpty then
            throw new IllegalArgumentException("choose exactly zero or one corpus filter")
          corpus = Some("commonmark")
        case "--gfm" =>
          if corpus.nonEmpty then
            throw new IllegalArgumentException("choose exactly zero or one corpus filter")
          corpus = Some("gfm")
        case "--example" if index + 1 < args.length =>
          if example.nonEmpty then
            throw new IllegalArgumentException("--example may appear at most once")
          example = args(index + 1).toIntOption.filter(_ > 0)
          if example.isEmpty then
            throw new IllegalArgumentException(s"invalid --example value: ${args(index + 1)}")
          index += 1
        case other =>
          throw new IllegalArgumentException(s"unknown or incomplete argument: $other")
      index += 1
    MarkdownCorpusData.all.filter { testCase =>
      corpus.forall(_ == testCase.corpus) && example.forall(_ == testCase.example)
    }

/** Deterministic machine-readable snapshot used to regenerate BASELINE.tsv. */
object MarkdownCorpusBaselineDump:
  def main(args: Array[String]): Unit =
    if args.nonEmpty then
      throw new IllegalArgumentException("MarkdownCorpusBaselineDump takes no arguments")
    val report = MarkdownCorpusRunner.baselineCensus()
    println(s"BASELINE-FULL-DIGEST\t${report.fullDigest}")
    println(s"BASELINE-NONPASS-DIGEST\t${report.nonPassDigest}")
    println(s"BASELINE-SECTION-DIGEST\t${report.sectionDigest}")
    report.rows.foreach(row => println(s"BASELINE-AXIS\t${row.tsv}"))
    report.sections.foreach(section => println(s"BASELINE-SECTION\t${section.tsv}"))
