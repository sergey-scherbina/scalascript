package scalascript.uniml.dialect.markdown.corpus

import scalascript.uniml.dialect.markdown.corpus.generated.MarkdownCorpusGenerated

/** One immutable upstream conformance example.
  *
  * `extension` is empty for CommonMark and names the enabled GFM extension for
  * GFM examples.  The upstream example number is the stable case id.
  */
final case class MarkdownCorpusCase(
    corpus: String,
    version: String,
    profile: String,
    example: Int,
    section: String,
    markdown: String,
    html: String,
    extension: String,
):
  def id: String = s"$corpus-$version-example-$example"

object MarkdownCorpusData:
  private val CommonMarkDigest =
    "f636418b09346809aa605ee4d52c3e600bf0f057251b77c386e49fae67a184a3"
  private val GfmDigest =
    "56ec730753789fa2a39db08f0dbfe7b63c9eec3b612494ff3fb0f75fef1facdd"

  val axes: Vector[String] =
    Vector("source", "tokens", "status", "html", "chunks")

  val commonMark: Vector[MarkdownCorpusCase] = MarkdownCorpusGenerated.commonMark
  val gfmEnabledExtensions: Vector[MarkdownCorpusCase] =
    MarkdownCorpusGenerated.gfmEnabledExtensions
  val all: Vector[MarkdownCorpusCase] = commonMark ++ gfmEnabledExtensions
  val baselineRows: Vector[MarkdownBaselineRow] =
    MarkdownCorpusGenerated.baselineRows

  val commonMarkCanonicalSha256: String =
    MarkdownCorpusGenerated.commonMarkCanonicalSha256
  val gfmCanonicalSha256: String =
    MarkdownCorpusGenerated.gfmCanonicalSha256
  val baselineFullRowsSha256: String =
    MarkdownCorpusGenerated.baselineFullRowsSha256
  val baselineNonPassRowsSha256: String =
    MarkdownCorpusGenerated.baselineNonPassRowsSha256
  val baselineSectionSha256: String =
    MarkdownCorpusGenerated.baselineSectionSha256

  private val expectedGfmRoster: Vector[(Int, String)] =
    Vector(
      198 -> "table",
      199 -> "table",
      200 -> "table",
      201 -> "table",
      202 -> "table",
      203 -> "table",
      204 -> "table",
      205 -> "table",
      279 -> "tasklist",
      280 -> "tasklist",
      491 -> "strikethrough",
      492 -> "strikethrough",
      621 -> "autolink",
      622 -> "autolink",
      623 -> "autolink",
      624 -> "autolink",
      625 -> "autolink",
      626 -> "autolink",
      627 -> "autolink",
      628 -> "autolink",
      629 -> "autolink",
      630 -> "autolink",
      631 -> "autolink",
    )

  private[corpus] def corpusAuthenticationProblems(
      commonMarkCases: Vector[MarkdownCorpusCase],
      gfmCases: Vector[MarkdownCorpusCase],
      pinnedCommonMarkDigest: String,
      pinnedGfmDigest: String,
  ): Vector[String] =
    val problems = Vector.newBuilder[String]
    val expectedCommonMarkIds = (1 to 652).toVector
    val actualCommonMarkIds = commonMarkCases.map(_.example)
    if actualCommonMarkIds != expectedCommonMarkIds then
      problems +=
        s"CommonMark roster differs: expected ids=1..652 actual-count=${actualCommonMarkIds.size}"
    if commonMarkCases.exists(testCase =>
        testCase.corpus != "commonmark" ||
          testCase.version != "0.31.2" ||
          testCase.profile != "commonmark" ||
          testCase.extension.nonEmpty
      )
    then problems += "CommonMark corpus/version/profile/extension metadata differs"

    val actualGfmRoster = gfmCases.map(testCase => testCase.example -> testCase.extension)
    if actualGfmRoster != expectedGfmRoster then
      problems +=
        s"GFM enabled-extension roster differs: expected=$expectedGfmRoster actual=$actualGfmRoster"
    if gfmCases.exists(testCase =>
        testCase.corpus != "gfm" ||
          testCase.version != "0.29" ||
          testCase.profile != "gfm"
      )
    then problems += "GFM corpus/version/profile metadata differs"

    val cases = commonMarkCases ++ gfmCases
    if cases.map(_.id).distinct.size != cases.size then
      problems += s"duplicate corpus ids: total=${cases.size} distinct=${cases.map(_.id).distinct.size}"

    val actualCommonMarkDigest = MarkdownCorpusSha256.canonical(commonMarkCases)
    val actualGfmDigest = MarkdownCorpusSha256.canonical(gfmCases)
    if pinnedCommonMarkDigest != CommonMarkDigest then
      problems +=
        s"generated CommonMark digest differs: expected=$CommonMarkDigest actual=$pinnedCommonMarkDigest"
    if pinnedGfmDigest != GfmDigest then
      problems += s"generated GFM digest differs: expected=$GfmDigest actual=$pinnedGfmDigest"
    if actualCommonMarkDigest != CommonMarkDigest then
      problems +=
        s"computed CommonMark digest differs: expected=$CommonMarkDigest actual=$actualCommonMarkDigest"
    if actualGfmDigest != GfmDigest then
      problems += s"computed GFM digest differs: expected=$GfmDigest actual=$actualGfmDigest"
    problems.result()

  private[corpus] def baselineAuthenticationProblems(
      cases: Vector[MarkdownCorpusCase],
      rows: Vector[MarkdownBaselineRow],
      pinnedFullDigest: String,
  ): Vector[String] =
    val problems = Vector.newBuilder[String]
    val byCase =
      cases.map(testCase =>
        (testCase.corpus, testCase.version, testCase.example) -> testCase
      ).toMap
    val expectedKeys =
      cases
        .flatMap(testCase =>
          axes.map(axis =>
            (testCase.corpus, testCase.version, testCase.example, axis)
          )
        )
        .sortBy(identity)
    val actualKeys = rows.map(_.key)
    if actualKeys != expectedKeys then
      problems +=
        s"baseline roster differs: expected=${expectedKeys.size} ordered rows actual=${actualKeys.size}"
    if actualKeys.distinct.size != actualKeys.size then
      problems +=
        s"duplicate baseline keys: total=${actualKeys.size} distinct=${actualKeys.distinct.size}"

    rows.foreach { row =>
      byCase.get((row.corpus, row.version, row.example)) match
        case None =>
          problems += s"baseline row has unknown case: ${row.corpus}/${row.version}/${row.example}"
        case Some(testCase) =>
          if row.profile != testCase.profile ||
              row.section != testCase.section ||
              row.extension != testCase.extension
          then
            problems +=
              s"baseline metadata differs for ${testCase.id}/${row.axis}"
      if !axes.contains(row.axis) then problems += s"baseline axis is unknown: ${row.axis}"
      if row.status != "MATCH" && row.status != "DIFF" && row.status != "ERROR" then
        problems += s"baseline status is unknown: ${row.status}"
      if row.status == "MATCH" && row.expectedSha256 != row.actualSha256 then
        problems += s"MATCH row has unequal observables: ${row.key}"
      Vector(
        "expected" -> row.expectedSha256,
        "actual" -> row.actualSha256,
        "diagnostics" -> row.diagnosticsSha256,
        "tree" -> row.treeSha256,
      ).foreach { case (label, digest) =>
        if !isSha256(digest) then
          problems += s"baseline $label digest is invalid for ${row.key}: $digest"
      }
    }

    val actualFullDigest = MarkdownCorpusSha256.fields(rows.flatMap(_.fields))
    if !isSha256(pinnedFullDigest) then
      problems += s"generated full-baseline digest is invalid: $pinnedFullDigest"
    else if actualFullDigest != pinnedFullDigest then
      problems +=
        s"full-baseline digest differs: expected=$pinnedFullDigest actual=$actualFullDigest"
    problems.result()

  private def isSha256(value: String): Boolean =
    value.length == 64 && value.forall(character =>
      (character >= '0' && character <= '9') ||
        (character >= 'a' && character <= 'f')
    )

  private[corpus] def requireCorporaAuthenticated(): Unit =
    val problems = corpusAuthenticationProblems(
      commonMark,
      gfmEnabledExtensions,
      commonMarkCanonicalSha256,
      gfmCanonicalSha256,
    )
    if problems.nonEmpty then
      throw new IllegalStateException(
        "Markdown corpus authentication failed:\n" + problems.mkString("\n")
      )

  private[corpus] def requireAuthenticated(): Unit =
    requireCorporaAuthenticated()
    val problems =
      baselineAuthenticationProblems(all, baselineRows, baselineFullRowsSha256)
    if problems.nonEmpty then
      throw new IllegalStateException(
        "Markdown baseline authentication failed:\n" + problems.mkString("\n")
      )
