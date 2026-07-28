package scalascript.uniml.dialect.yaml

import scalascript.uniml.*
import scala.util.control.NonFatal

private[yaml] final case class YamlOfficialCase(
    id: String,
    title: String,
    input: String,
    expectedEvents: Vector[YamlNormalizedEvent],
    shouldFail: Boolean,
    categories: Vector[String],
)

private[yaml] final case class YamlNormalizedEvent(
    kind: String,
    anchor: Option[String] = None,
    tag: Option[String] = None,
    value: Option[String] = None,
):
  def display: String =
    val properties = Vector(anchor.map("&" + _), tag.map("<" + _ + ">")).flatten
    val suffix = value.map(text => " " + YamlCorpusText.escape(text)).getOrElse("")
    (kind +: properties).mkString(" ") + suffix

private[yaml] final case class YamlDiagnosticKey(
    severity: String,
    code: String,
)

private[yaml] final case class YamlChunkObservation(
    name: String,
    splitOffsets: Vector[Int],
    parseSnapshotSha256: Option[String],
    reconstructedSourceSha256: Option[String],
    reconstructionProblems: Vector[String],
    axisErrors: Vector[String],
):
  def canonical: String =
    YamlCorpusCanonical.record(
      Vector(
        name,
        splitOffsets.mkString(","),
        YamlCorpusCanonical.option(parseSnapshotSha256),
        YamlCorpusCanonical.option(reconstructedSourceSha256),
        reconstructionProblems.size.toString,
        YamlCorpusCanonical.digest(reconstructionProblems),
        axisErrors.size.toString,
        YamlCorpusCanonical.digest(axisErrors),
      )
    )

private[yaml] final case class YamlCorpusOutcome(
    testCase: YamlOfficialCase,
    reconstructedSource: String,
    reconstructionProblems: Vector[String],
    sourceExact: Boolean,
    sourceAxisError: Option[String],
    chunkObservations: Vector[YamlChunkObservation],
    chunkProblems: Vector[String],
    chunkExact: Boolean,
    expectedValid: Boolean,
    actualValid: Boolean,
    validityObserved: Boolean,
    actualStatus: String,
    expectedEvents: Vector[YamlNormalizedEvent],
    actualEvents: Vector[YamlNormalizedEvent],
    diagnostics: Vector[YamlDiagnosticKey],
    semanticAxisError: Option[String],
    outerAxisError: Option[String],
):
  private def observedAxisErrors: Vector[String] =
    Vector(
      sourceAxisError.map("source: " + _),
      semanticAxisError.map("semantic: " + _),
      outerAxisError.map("outer: " + _),
    ).flatten ++ chunkObservations.flatMap { observation =>
      observation.axisErrors.map(error => s"chunk:${observation.name}: $error")
    }

  private lazy val baselineRowCapture: YamlCaptured[String] =
    YamlCaptured(buildBaselineRow(observedAxisErrors))

  private def buildBaselineRow(errors: Vector[String]): String =
    val expectedSourceSha256 = YamlCorpusSha256.digestUtf8(testCase.input)
    val actualSourceSha256 = YamlCorpusSha256.digestUtf8(reconstructedSource)
    val expectedEventsSha256 = YamlCorpusCanonical.eventsSha256(expectedEvents)
    val actualEventsSha256 = YamlCorpusCanonical.eventsSha256(actualEvents)
    val chunkCanonical = chunkObservations.map(_.canonical)
    val fields = Vector(
      YamlCorpusText.canonicalText(testCase.id),
      s"source.expected=$expectedSourceSha256",
      s"source.actual=$actualSourceSha256",
      s"source.problems=${reconstructionProblems.size}:${YamlCorpusCanonical.digest(reconstructionProblems)}",
      s"source.error=${YamlCorpusCanonical.optionDigest(sourceAxisError)}",
      s"status.expected=${if expectedValid then "valid" else "error"}",
      s"status.actual=${if validityObserved then if actualValid then "valid" else "error" else "unobserved"}",
      s"status.completion=$actualStatus",
      s"diagnostics=${diagnostics.size}:${YamlCorpusCanonical.diagnosticsSha256(diagnostics)}",
      s"events.expected=${expectedEvents.size}:$expectedEventsSha256",
      s"events.actual=${actualEvents.size}:$actualEventsSha256",
      s"events.error=${YamlCorpusCanonical.optionDigest(semanticAxisError)}",
      s"chunks=${chunkObservations.size}:${YamlCorpusCanonical.digest(chunkCanonical)}",
      s"axis-errors=${errors.size}:${YamlCorpusCanonical.digest(errors)}",
    )
    fields.mkString("\t")

  private def fallbackBaselineRow(error: String): String =
    val fields = Vector(
      YamlCorpusText.canonicalText(testCase.id),
      "baseline.error=" + YamlCorpusText.utf16Sha256(error),
      "baseline.error.utf16=" + YamlCorpusText.utf16Hex(error),
      fallbackUtf16Field("source.expected.utf16", testCase.input),
      fallbackUtf16Field("source.actual.utf16", reconstructedSource),
      s"source.problems.count=${reconstructionProblems.size}",
      fallbackUtf16Field(
        "source.problems.utf16",
        YamlCorpusCanonical.rawStrings(reconstructionProblems),
      ),
      fallbackUtf16Field(
        "source.error.utf16",
        YamlCorpusCanonical.option(sourceAxisError),
      ),
      s"source.exact=$sourceExact",
      s"status.expected=${if expectedValid then "valid" else "error"}",
      s"status.actual=${if validityObserved then if actualValid then "valid" else "error" else "unobserved"}",
      fallbackUtf16Field("status.completion.utf16", actualStatus),
      s"diagnostics.count=${diagnostics.size}",
      fallbackUtf16Field(
        "diagnostics.utf16",
        YamlCorpusCanonical.rawDiagnostics(diagnostics),
      ),
      s"events.expected.count=${expectedEvents.size}",
      fallbackUtf16Field(
        "events.expected.utf16",
        YamlCorpusCanonical.rawEvents(expectedEvents),
      ),
      s"events.actual.count=${actualEvents.size}",
      fallbackUtf16Field(
        "events.actual.utf16",
        YamlCorpusCanonical.rawEvents(actualEvents),
      ),
      fallbackUtf16Field(
        "events.error.utf16",
        YamlCorpusCanonical.option(semanticAxisError),
      ),
      s"chunks.count=${chunkObservations.size}",
      fallbackUtf16Field(
        "chunks.utf16",
        YamlCorpusCanonical.rawChunks(chunkObservations),
      ),
      s"chunks.exact=$chunkExact",
      s"axis-errors.count=${observedAxisErrors.size}",
      fallbackUtf16Field(
        "axis-errors.utf16",
        YamlCorpusCanonical.rawStrings(observedAxisErrors),
      ),
    )
    fields.mkString("\t")

  private def fallbackUtf16Field(label: String, rawValue: => String): String =
    val captured = YamlCaptured {
      val value = rawValue
      s"$label=${value.length}:${YamlCorpusText.utf16Sha256(value)}"
    }
    captured.value.getOrElse {
      val error = captured.error.getOrElse("fallback field unavailable")
      s"$label=unavailable:${YamlCorpusText.utf16Sha256(error)}"
    }

  private def emergencyBaselineRow(primaryError: String, fallbackError: String): String =
    val caseId =
      YamlCaptured(YamlCorpusText.canonicalText(testCase.id)).value
        .getOrElse("<case-id-unavailable>")
    Vector(
      caseId,
      "baseline.error=" + YamlCorpusText.utf16Sha256(primaryError),
      "baseline.fallback.error=" + YamlCorpusText.utf16Sha256(fallbackError),
    ).mkString("\t")

  def baselineAxisError: Option[String] = baselineRowCapture.error
  def semanticsExact: Boolean = semanticAxisError.isEmpty && expectedEvents == actualEvents
  def validityExact: Boolean =
    validityObserved && expectedValid == actualValid
  def axisErrors: Vector[String] =
    observedAxisErrors ++ baselineAxisError.map("baseline: " + _)
  def crash: Option[String] = axisErrors.headOption
  def strictExact: Boolean =
    sourceExact && chunkExact && validityExact && semanticsExact && axisErrors.isEmpty

  def baselineRow: String =
    baselineRowCapture.value.getOrElse {
      val primaryError = baselineRowCapture.error.getOrElse("baseline row unavailable")
      val fallback = YamlCaptured(fallbackBaselineRow(primaryError))
      fallback.value.getOrElse(
        emergencyBaselineRow(
          primaryError,
          fallback.error.getOrElse("baseline fallback unavailable"),
        )
      )
    }

private[yaml] final case class YamlCorpusCensus(
    total: Int,
    expectedErrors: Int,
    actualErrors: Int,
    sourceExact: Int,
    chunkExact: Int,
    validityExact: Int,
    semanticsExact: Int,
    strictExact: Int,
    crashes: Int,
):
  def summary: String =
    s"cases=$total expected-errors=$expectedErrors actual-errors=$actualErrors " +
    s"source=$sourceExact/$total chunks=$chunkExact/$total validity=$validityExact/$total " +
      s"semantics=$semanticsExact/$total strict=$strictExact/$total crashes=$crashes"

private[yaml] final case class YamlCorpusCategoryCensus(
    category: String,
    total: Int,
    sourceExact: Int,
    chunkExact: Int,
    validityExact: Int,
    semanticsExact: Int,
    strictExact: Int,
    crashes: Int,
):
  def summary: String =
    s"category=$category cases=$total source=$sourceExact/$total chunks=$chunkExact/$total " +
      s"validity=$validityExact/$total semantics=$semanticsExact/$total " +
      s"strict=$strictExact/$total crashes=$crashes"

private[yaml] final case class YamlCorpusReport(
    outcomes: Vector[YamlCorpusOutcome],
    census: YamlCorpusCensus,
):
  def failures: Vector[YamlCorpusOutcome] = outcomes.filterNot(_.strictExact)
  private lazy val baselineDigestCapture: YamlCaptured[String] =
    YamlCaptured(YamlCorpusSha256.digestUtf8(baselineRows.mkString("", "\n", "\n")))
  private lazy val categoryDigestCapture: YamlCaptured[String] =
    YamlCaptured(YamlCorpusSha256.digestUtf8(categoryRows.mkString("", "\n", "\n")))

  def aggregateErrors: Vector[String] =
    Vector(
      baselineDigestCapture.error.map("baseline digest: " + _),
      categoryDigestCapture.error.map("category digest: " + _),
    ).flatten
  def isStrictGreen: Boolean =
    outcomes.nonEmpty && failures.isEmpty && aggregateErrors.isEmpty
  def baselineRows: Vector[String] = outcomes.map(_.baselineRow).sorted
  def baselineDigest: String =
    baselineDigestCapture.value.getOrElse("unavailable")
  def baselineDigestError: Option[String] = baselineDigestCapture.error

  def categoryCensus: Vector[YamlCorpusCategoryCensus] =
    outcomes.flatMap(outcome => outcome.testCase.categories.map(_ -> outcome)).groupBy(_._1).toVector
      .sortBy(_._1)
      .map { pair =>
        val category = pair._1
        val members = pair._2.map(_._2)
        YamlCorpusCategoryCensus(
          category = category,
          total = members.size,
          sourceExact = members.count(_.sourceExact),
          chunkExact = members.count(_.chunkExact),
          validityExact = members.count(_.validityExact),
          semanticsExact = members.count(_.semanticsExact),
          strictExact = members.count(_.strictExact),
          crashes = members.count(_.crash.nonEmpty),
        )
      }
  def categoryRows: Vector[String] = categoryCensus.map(_.summary)
  def categoryDigest: String =
    categoryDigestCapture.value.getOrElse("unavailable")
  def categoryDigestError: Option[String] = categoryDigestCapture.error

private[yaml] object YamlOfficialCorpus:
  val cases: Vector[YamlOfficialCase] = YamlOfficialCorpusData.cases.map { encoded =>
    YamlOfficialCase(
      id = encoded.id,
      title = YamlCorpusText.stripLineEnd(YamlCorpusUtf8.decodeHex(encoded.titleUtf8Hex)),
      input = YamlCorpusUtf8.decodeHex(encoded.inputUtf8Hex),
      expectedEvents = YamlEventNormalization.fromOfficial(
        YamlCorpusUtf8.decodeHex(encoded.eventsUtf8Hex)
      ),
      shouldFail = encoded.shouldFail,
      categories = encoded.categories,
    )
  }

  val canonicalEncodedData: String = canonicalEncodedData(YamlOfficialCorpusData.cases)

  def canonicalEncodedData(encodedCases: Vector[YamlOfficialCaseData]): String =
    encodedCases.map { encoded =>
      Vector(
        encoded.id,
        encoded.titleUtf8Hex,
        encoded.inputUtf8Hex,
        encoded.eventsUtf8Hex,
        if encoded.shouldFail then "1" else "0",
      ).mkString("\t") + "\n"
    }.mkString

  val canonicalCategories: String = canonicalCategories(YamlOfficialCorpusData.cases)

  def canonicalCategories(encodedCases: Vector[YamlOfficialCaseData]): String =
    encodedCases.flatMap { encoded =>
      encoded.categories.map(tag => s"${encoded.id}\t$tag\n")
    }.mkString

private[yaml] object YamlOfficialCorpusIntegrity:
  def problems(
      encodedCases: Vector[YamlOfficialCaseData],
      expectedCaseCount: Int,
      expectedErrorCount: Int,
      expectedLogicalSha256: String,
      expectedCategoriesSha256: String,
      encodingExclusions: Vector[String],
  ): Vector[String] =
    val result = Vector.newBuilder[String]
    val ids = encodedCases.map(_.id)
    if encodedCases.size != expectedCaseCount then
      result += s"case count expected=$expectedCaseCount actual=${encodedCases.size}"
    val errors = encodedCases.count(_.shouldFail)
    if errors != expectedErrorCount then
      result += s"error count expected=$expectedErrorCount actual=$errors"
    if ids != ids.sorted then result += "case roster is not sorted"
    if ids.distinct.size != ids.size then result += "case roster contains duplicate ids"
    encodedCases.foreach { encoded =>
      if encoded.categories.isEmpty then result += s"${encoded.id}: no upstream categories"
      if encoded.categories != encoded.categories.sorted.distinct then
        result += s"${encoded.id}: categories are not sorted and unique"
      encoded.categories.foreach { category =>
        if category.isEmpty || category.exists(_.isWhitespace) then
          result += s"${encoded.id}: invalid category '$category'"
      }
    }
    if encodingExclusions.nonEmpty then
      result += "stale encoding exclusions: " + encodingExclusions.sorted.mkString(",")
    val actualDigest = YamlCorpusSha256.digestAscii(
      YamlOfficialCorpus.canonicalEncodedData(encodedCases)
    )
    if actualDigest != expectedLogicalSha256 then
      result += s"logical SHA-256 expected=$expectedLogicalSha256 actual=$actualDigest"
    val actualCategoriesDigest = YamlCorpusSha256.digestAscii(
      YamlOfficialCorpus.canonicalCategories(encodedCases)
    )
    if actualCategoriesDigest != expectedCategoriesSha256 then
      result +=
        s"categories SHA-256 expected=$expectedCategoriesSha256 actual=$actualCategoriesDigest"
    result.result()

  def pinnedProblems: Vector[String] =
    problems(
      YamlOfficialCorpusData.cases,
      YamlOfficialCorpusData.expectedCaseCount,
      YamlOfficialCorpusData.expectedErrorCount,
      YamlOfficialCorpusData.logicalSha256,
      YamlOfficialCorpusData.categoriesSha256,
      YamlOfficialCorpusData.encodingExclusions,
    )

private[yaml] final case class YamlCorpusHooks(
    parse: SourceInput => ParseResult,
    semantic: (SourceId, String) => YamlSemanticResult,
)

private[yaml] object YamlCorpusHooks:
  val default: YamlCorpusHooks = YamlCorpusHooks(
    parse = source => Yaml.parse(source),
    semantic = (source, input) => YamlSemanticParser.parse(source, input, YamlSchema.Failsafe),
  )

private[yaml] final case class YamlCaptured[+A](
    value: Option[A],
    error: Option[String],
)

private[yaml] object YamlCaptured:
  def apply[A](body: => A): YamlCaptured[A] =
    try YamlCaptured(Some(body), None)
    catch
      case error: StackOverflowError => YamlCaptured(None, Some(error.toString))
      case NonFatal(error)           => YamlCaptured(None, Some(error.toString))

  def unavailable[A](reason: String): YamlCaptured[A] =
    YamlCaptured(None, Some(reason))

private final case class YamlScheduleEvaluation(
    schedule: YamlChunkSchedule,
    parsed: YamlCaptured[ParseResult],
    snapshot: YamlCaptured[String],
    reconstruction: YamlCaptured[YamlReconstruction],
):
  def observation: YamlChunkObservation =
    val reconstructedSourceDigest = reconstruction.value match
      case Some(result) => YamlCaptured(YamlCorpusSha256.digestUtf8(result.text))
      case None         => YamlCaptured[String](None, None)
    YamlChunkObservation(
      name = schedule.name,
      splitOffsets = YamlCorpusChunks.splitOffsets(schedule),
      parseSnapshotSha256 = snapshot.value,
      reconstructedSourceSha256 = reconstructedSourceDigest.value,
      reconstructionProblems = reconstruction.value.map(_.problems).getOrElse(Vector.empty),
      axisErrors = Vector(
        parsed.error.map("parse: " + _),
        snapshot.error.map("snapshot: " + _),
        reconstruction.error.map("reconstruction: " + _),
        reconstructedSourceDigest.error.map("reconstructed-source-digest: " + _),
      ).flatten,
    )

private[yaml] object YamlOfficialCorpusGate:
  def evaluate(hooks: YamlCorpusHooks = YamlCorpusHooks.default): YamlCorpusReport =
    evaluateCases(YamlOfficialCorpus.cases)(testCase => evaluateUnsafe(testCase, hooks))

  def evaluateCases(
      testCases: Vector[YamlOfficialCase]
  )(evaluator: YamlOfficialCase => YamlCorpusOutcome): YamlCorpusReport =
    report(testCases.map(testCase => safely(testCase, evaluator)))

  def evaluateOne(testCase: YamlOfficialCase): YamlCorpusOutcome =
    evaluateOne(testCase, YamlCorpusHooks.default)

  def evaluateOne(testCase: YamlOfficialCase, hooks: YamlCorpusHooks): YamlCorpusOutcome =
    safely(testCase, value => evaluateUnsafe(value, hooks))

  private def safely(
      testCase: YamlOfficialCase,
      evaluator: YamlOfficialCase => YamlCorpusOutcome,
  ): YamlCorpusOutcome =
    try evaluator(testCase)
    catch
      case error: StackOverflowError => crashedOutcome(testCase, error.toString)
      case NonFatal(error)           => crashedOutcome(testCase, error.toString)

  private def crashedOutcome(
      testCase: YamlOfficialCase,
      rendered: String,
  ): YamlCorpusOutcome =
    YamlCorpusOutcome(
      testCase = testCase,
      reconstructedSource = "",
      reconstructionProblems = Vector("case crashed before reconstruction: " + rendered),
      sourceExact = false,
      sourceAxisError = Some(rendered),
      chunkObservations = Vector.empty,
      chunkProblems = Vector("case crashed before chunk comparison: " + rendered),
      chunkExact = false,
      expectedValid = !testCase.shouldFail,
      actualValid = false,
      validityObserved = false,
      actualStatus = "unobserved",
      expectedEvents = testCase.expectedEvents,
      actualEvents = Vector.empty,
      diagnostics = Vector.empty,
      semanticAxisError = Some(rendered),
      outerAxisError = Some(rendered),
    )

  private def evaluateUnsafe(
      testCase: YamlOfficialCase,
      hooks: YamlCorpusHooks,
  ): YamlCorpusOutcome =
    val sourceId = SourceId("yaml-test-suite:" + testCase.id)
    val schedules = YamlCorpusChunks.schedules(testCase.input)
    val evaluated = schedules.map { schedule =>
      val parsed = YamlCaptured {
        hooks.parse(SourceInput(sourceId, schedule.chunks.map(SourceChunk.apply)))
      }
      val snapshot = parsed.value match
        case Some(result) => YamlCaptured(YamlCorpusSnapshot.sha256(result))
        case None         => YamlCaptured.unavailable("parse result unavailable")
      val reconstruction = parsed.value match
        case Some(result) =>
          YamlCaptured(YamlCorpusReconstruction.inspect(result, sourceId, testCase.input))
        case None => YamlCaptured.unavailable("parse result unavailable")
      YamlScheduleEvaluation(schedule, parsed, snapshot, reconstruction)
    }
    val semantic = YamlCaptured {
      val result = hooks.semantic(sourceId, testCase.input)
      result -> YamlEventNormalization.fromActual(result.stream)
    }
    val observations = evaluated.map(_.observation)
    val whole = evaluated.head
    val wholeObservation = observations.head
    val referenceSnapshot = whole.snapshot.value
    val inputSha256 = YamlCaptured(YamlCorpusSha256.digestUtf8(testCase.input))
    val chunkProblems =
      inputSha256.error.map("expected-source-digest: " + _).toVector ++
        observations.flatMap { observation =>
          val problems = Vector.newBuilder[String]
          observation.axisErrors.foreach(error =>
            problems += s"schedule=${observation.name} $error"
          )
          if observation.parseSnapshotSha256 != referenceSnapshot then
            problems +=
              s"schedule=${observation.name} parse snapshot differs from schedule=${whole.schedule.name}"
          if
            inputSha256.value.nonEmpty &&
            observation.reconstructedSourceSha256 != inputSha256.value
          then
            problems += s"schedule=${observation.name} reconstructed source differs"
          observation.reconstructionProblems.foreach(problem =>
            problems += s"schedule=${observation.name} $problem"
          )
          problems.result()
        }

    val parsedDiagnostics = whole.parsed.value.map(_.diagnostics).getOrElse(Vector.empty)
    val semanticDiagnostics = semantic.value.map(_._1.diagnostics).getOrElse(Vector.empty)
    val allDiagnostics = parsedDiagnostics ++ semanticDiagnostics
    val diagnosticKeys =
      allDiagnostics.map(diagnostic =>
        YamlDiagnosticKey(diagnostic.severity.toString, diagnostic.code)
      )
    val validityObserved = whole.parsed.value.nonEmpty && semantic.value.nonEmpty
    val hasError = allDiagnostics.exists { diagnostic =>
      diagnostic.severity == Severity.Error || diagnostic.severity == Severity.Fatal
    }
    val actualValid =
      validityObserved &&
        whole.parsed.value.exists(_.status == CompletionStatus.Complete) &&
        !hasError
    val reconstruction =
      whole.reconstruction.value.getOrElse(YamlReconstruction("", Vector.empty))
    val sourceAxisError =
      whole.parsed.error.map("parse: " + _).orElse(
        whole.reconstruction.error.map("reconstruction: " + _)
      ).orElse(
        inputSha256.error.map("expected-source-digest: " + _)
      )
    val sourceExact =
      sourceAxisError.isEmpty &&
        reconstruction.text == testCase.input &&
        reconstruction.problems.isEmpty
    val actualStatus = whole.parsed.value.map(_.status.toString).getOrElse("unobserved")

    YamlCorpusOutcome(
      testCase = testCase,
      reconstructedSource = reconstruction.text,
      reconstructionProblems = reconstruction.problems,
      sourceExact = sourceExact,
      sourceAxisError = sourceAxisError,
      chunkObservations = wholeObservation +: observations.tail,
      chunkProblems = chunkProblems,
      chunkExact = chunkProblems.isEmpty,
      expectedValid = !testCase.shouldFail,
      actualValid = actualValid,
      validityObserved = validityObserved,
      actualStatus = actualStatus,
      expectedEvents = testCase.expectedEvents,
      actualEvents = semantic.value.map(_._2).getOrElse(Vector.empty),
      diagnostics = diagnosticKeys,
      semanticAxisError = semantic.error,
      outerAxisError = None,
    )

  def report(outcomes: Vector[YamlCorpusOutcome]): YamlCorpusReport =
    val total = outcomes.size
    YamlCorpusReport(
      outcomes,
      YamlCorpusCensus(
        total = total,
        expectedErrors = outcomes.count(_.testCase.shouldFail),
        actualErrors = outcomes.count(!_.actualValid),
        sourceExact = outcomes.count(_.sourceExact),
        chunkExact = outcomes.count(_.chunkExact),
        validityExact = outcomes.count(_.validityExact),
        semanticsExact = outcomes.count(_.semanticsExact),
        strictExact = outcomes.count(_.strictExact),
        crashes = outcomes.count(_.crash.nonEmpty),
      ),
    )

  def printCensus(report: YamlCorpusReport): Unit =
    requireIntegrityAndRoster(report)
    println(
      s"yaml-test-suite ${YamlOfficialCorpusData.version} " +
        s"revision=${YamlOfficialCorpusData.revision} ${report.census.summary}"
    )
    report.categoryCensus.foreach(category => println("  " + category.summary))
    println(
      s"baseline-sha256=${report.baselineDigest} rows=${report.baselineRows.size} " +
        s"category-sha256=${report.categoryDigest} categories=${report.categoryRows.size}"
    )
    report.aggregateErrors.foreach(error =>
      println("  aggregate-error=" + YamlCorpusText.canonicalText(error))
    )

  def printFailures(report: YamlCorpusReport): Unit =
    report.failures.foreach(outcome => println(renderFailureSafely(outcome)))

  def requireIntegrityAndRoster(report: YamlCorpusReport): Unit =
    val problems = Vector.newBuilder[String]
    YamlOfficialCorpusIntegrity.pinnedProblems.foreach(problems += _)
    val expectedIds = YamlOfficialCorpus.cases.map(_.id)
    val actualIds = report.outcomes.map(_.testCase.id)
    if actualIds != expectedIds then
      problems +=
        s"case roster expected=${expectedIds.size} actual=${actualIds.size}; " +
          YamlCorpusDiff.firstVectorDifference(expectedIds, actualIds)
    val expectedCases = YamlOfficialCorpus.cases
    val actualCases = report.outcomes.map(_.testCase)
    if actualCases != expectedCases then
      val count = math.max(expectedCases.size, actualCases.size)
      val mismatch =
        (0 until count).find(index => expectedCases.lift(index) != actualCases.lift(index))
      mismatch.foreach { index =>
        val expected = expectedCases.lift(index)
        val actual = actualCases.lift(index)
        problems +=
          s"case identity mismatch index=$index " +
            s"expected-id=${expected.map(value => YamlCorpusText.canonicalText(value.id)).getOrElse("<missing>")} " +
            s"actual-id=${actual.map(value => YamlCorpusText.canonicalText(value.id)).getOrElse("<missing>")}"
      }
    if report.census.total != expectedIds.size then
      problems += s"census total expected=${expectedIds.size} actual=${report.census.total}"
    val found = problems.result()
    if found.nonEmpty then
      throw new IllegalStateException(
        "YAML corpus integrity/roster gate failed:\n" +
          found.map("  " + _).mkString("\n")
      )

  def requireCensus(report: YamlCorpusReport): Unit =
    requireIntegrityAndRoster(report)
    val problems = Vector.newBuilder[String]
    if report.census != YamlOfficialCorpusBaseline.census then
      problems +=
        s"census expected=${YamlOfficialCorpusBaseline.census.summary} " +
          s"actual=${report.census.summary}"
    if report.baselineDigest != YamlOfficialCorpusBaseline.baselineSha256 then
      problems +=
        s"baseline SHA-256 expected=${YamlOfficialCorpusBaseline.baselineSha256} " +
          s"actual=${report.baselineDigest}"
    if report.baselineRows != YamlOfficialCorpusBaseline.baselineRows then
      problems += YamlCorpusDiff.firstVectorDifference(
        YamlOfficialCorpusBaseline.baselineRows,
        report.baselineRows,
      )
    report.baselineDigestError.foreach(error =>
      problems += "baseline digest unavailable: " + YamlCorpusText.canonicalText(error)
    )
    if report.categoryDigest != YamlOfficialCorpusBaseline.categorySha256 then
      problems +=
        s"category SHA-256 expected=${YamlOfficialCorpusBaseline.categorySha256} " +
          s"actual=${report.categoryDigest}"
    if report.categoryRows != YamlOfficialCorpusBaseline.categoryRows then
      problems += YamlCorpusDiff.firstVectorDifference(
        YamlOfficialCorpusBaseline.categoryRows,
        report.categoryRows,
      )
    report.categoryDigestError.foreach(error =>
      problems += "category digest unavailable: " + YamlCorpusText.canonicalText(error)
    )
    val found = problems.result()
    if found.nonEmpty then
      throw new IllegalStateException(
        "YAML frozen census gate failed:\n" + found.map("  " + _).mkString("\n")
      )

  def requireStrict(report: YamlCorpusReport): Unit =
    requireIntegrityAndRoster(report)
    if !report.isStrictGreen then
      throw new IllegalStateException(
        s"YAML strict corpus gate failed: ${report.failures.size}/${report.census.total} " +
          s"cases mismatch, aggregate-errors=${report.aggregateErrors.size}"
      )

  def printBaselineCandidate(report: YamlCorpusReport): Unit =
    println("// Candidate generated by yamlOfficialCorpusBaselineCandidate; review before replacing.")
    println("package scalascript.uniml.dialect.yaml")
    println()
    println("private[yaml] object YamlOfficialCorpusBaseline:")
    println("  val census: YamlCorpusCensus = YamlCorpusCensus(")
    println(s"    total = ${report.census.total},")
    println(s"    expectedErrors = ${report.census.expectedErrors},")
    println(s"    actualErrors = ${report.census.actualErrors},")
    println(s"    sourceExact = ${report.census.sourceExact},")
    println(s"    chunkExact = ${report.census.chunkExact},")
    println(s"    validityExact = ${report.census.validityExact},")
    println(s"    semanticsExact = ${report.census.semanticsExact},")
    println(s"    strictExact = ${report.census.strictExact},")
    println(s"    crashes = ${report.census.crashes},")
    println("  )")
    println(s"""  val baselineSha256: String = "${report.baselineDigest}"""")
    println("  val baselineRows: Vector[String] = Vector(")
    report.baselineRows.foreach(row => println("    " + YamlCorpusText.scalaLiteral(row) + ","))
    println("  )")
    println(s"""  val categorySha256: String = "${report.categoryDigest}"""")
    println("  val categoryRows: Vector[String] = Vector(")
    report.categoryRows.foreach(row => println("    " + YamlCorpusText.scalaLiteral(row) + ","))
    println("  )")

  def renderFailure(outcome: YamlCorpusOutcome): String =
    val lines = Vector.newBuilder[String]
    lines += s"CASE ${outcome.testCase.id} — ${outcome.testCase.title}"
    outcome.axisErrors.foreach(value =>
      lines += "  axis-error actual=" + YamlCorpusText.escape(value)
    )
    lines += s"  status expected=${if outcome.expectedValid then "valid" else "error"} " +
      s"actual=${if outcome.validityObserved then if outcome.actualValid then "valid" else "error" else "unobserved"} " +
      s"completion=${outcome.actualStatus} diagnostics=" +
      outcome.diagnostics
        .map(key => s"${key.severity}:${key.code}")
        .mkString("[", ",", "]")
    if !outcome.validityExact then lines += "  status diff=validity classification mismatch"
    if !outcome.sourceExact then
      lines += "  source expected=" + YamlCorpusText.escape(outcome.testCase.input)
      lines += "  source actual=" + YamlCorpusText.escape(outcome.reconstructedSource)
      lines += "  source diff=" + YamlCorpusDiff.firstCharacterDifference(
        outcome.testCase.input,
        outcome.reconstructedSource,
      )
      outcome.reconstructionProblems.foreach(problem => lines += "  source invariant=" + problem)
    if !outcome.chunkExact then
      lines += "  chunk expected=all bounded schedules equal whole actual=mismatch"
      outcome.chunkProblems.foreach(problem => lines += "  chunk diff=" + problem)
    if !outcome.semanticsExact then
      lines += "  events expected:"
      outcome.expectedEvents.zipWithIndex.foreach { pair =>
        lines += f"    ${pair._2}%03d - ${pair._1.display}"
      }
      lines += "  events actual:"
      outcome.actualEvents.zipWithIndex.foreach { pair =>
        lines += f"    ${pair._2}%03d + ${pair._1.display}"
      }
      lines += "  events diff:"
      YamlCorpusDiff.eventDifferences(outcome.expectedEvents, outcome.actualEvents)
        .foreach(line => lines += "    " + line)
    lines.result().mkString("\n")

  def renderFailureSafely(outcome: YamlCorpusOutcome): String =
    renderFailureSafelyWith(outcome, renderFailure)

  private[yaml] def renderFailureSafelyWith(
      outcome: YamlCorpusOutcome,
      renderer: YamlCorpusOutcome => String,
  ): String =
    val rendered = YamlCaptured {
      val value = renderer(outcome)
      if value == null then
        throw new IllegalArgumentException("failure renderer returned null")
      if !YamlCorpusUtf8.isWellFormed(value) then
        throw new IllegalArgumentException("failure renderer returned ill-formed UTF-16")
      value
    }
    rendered.value.getOrElse {
      val error = rendered.error.getOrElse("failure rendering unavailable")
      val caseId =
        YamlCaptured(YamlCorpusText.canonicalText(outcome.testCase.id)).value
          .getOrElse("<case-id-unavailable>")
      s"CASE $caseId — <render unavailable>\n" +
        s"  render-error-sha256=${YamlCorpusText.utf16Sha256(error)} " +
        s"utf16=${YamlCorpusText.utf16Hex(error)}"
    }

@main def yamlOfficialCorpusCensus(): Unit =
  val report = YamlOfficialCorpusGate.evaluate()
  YamlOfficialCorpusGate.printCensus(report)
  YamlOfficialCorpusGate.requireCensus(report)

@main def yamlOfficialCorpusStrict(): Unit =
  val report = YamlOfficialCorpusGate.evaluate()
  YamlOfficialCorpusGate.printCensus(report)
  YamlOfficialCorpusGate.printFailures(report)
  YamlOfficialCorpusGate.requireStrict(report)

@main def yamlOfficialCorpusBaselineCandidate(): Unit =
  YamlOfficialCorpusGate.printBaselineCandidate(YamlOfficialCorpusGate.evaluate())

private[yaml] final case class YamlChunkSchedule(
    name: String,
    chunks: Vector[String],
)

private[yaml] object YamlCorpusChunks:
  def schedules(source: String): Vector[YamlChunkSchedule] =
    val generic = Vector(
      schedule("whole", source, Vector.empty),
      schedule("midpoint", source, Vector(source.length / 2)),
      schedule("edges", source, Vector(1, source.length - 1)),
      schedule(
        "quarters",
        source,
        Vector(source.length / 4, source.length / 2, (source.length * 3) / 4),
      ),
    )
    val crlf = Option.when(source.indexOf("\r\n") >= 0) {
      val start = source.indexOf("\r\n")
      schedule("first-crlf", source, Vector(start, start + 1, start + 2))
    }
    val astral = firstSurrogatePair(source).map { start =>
      schedule("first-astral", source, Vector(start, start + 1, start + 2))
    }
    val candidates = generic ++ Vector(crlf, astral).flatten
    candidates.foldLeft(Vector.empty[YamlChunkSchedule]) { (result, candidate) =>
      if result.exists(_.chunks == candidate.chunks) then result else result :+ candidate
    }

  def splitOffsets(schedule: YamlChunkSchedule): Vector[Int] =
    schedule.chunks.scanLeft(0)((offset, chunk) => offset + chunk.length)

  private def firstSurrogatePair(source: String): Option[Int] =
    var index = 0
    var result: Option[Int] = None
    while index + 1 < source.length && result.isEmpty do
      val first = source.charAt(index)
      val second = source.charAt(index + 1)
      if first >= '\uD800' && first <= '\uDBFF' && second >= '\uDC00' && second <= '\uDFFF' then
        result = Some(index)
      else index += 1
    result

  private def schedule(
      name: String,
      source: String,
      rawBoundaries: Vector[Int],
  ): YamlChunkSchedule =
    val boundaries = rawBoundaries.filter(index => index > 0 && index < source.length).distinct.sorted
    val points = 0 +: boundaries :+ source.length
    val chunks = points.sliding(2).map(pair => source.substring(pair.head, pair.last)).toVector
    YamlChunkSchedule(name, if chunks.isEmpty then Vector("") else chunks)

private[yaml] final case class YamlReconstruction(
    text: String,
    problems: Vector[String],
)

private[yaml] object YamlCorpusCanonical:
  def record(fields: Vector[String]): String =
    fields.map(value => s"${value.length}:$value").mkString("|") + "\n"

  def option(value: Option[String]): String =
    value match
      case Some(found) => "some:" + found
      case None        => "none"

  def rawStrings(values: Vector[String]): String =
    values.map(value => record(Vector(value))).mkString

  def rawEvents(events: Vector[YamlNormalizedEvent]): String =
    events.map { event =>
      record(
        Vector(
          event.kind,
          option(event.anchor),
          option(event.tag),
          option(event.value),
        )
      )
    }.mkString

  def rawDiagnostics(diagnostics: Vector[YamlDiagnosticKey]): String =
    diagnostics.map(key => record(Vector(key.severity, key.code))).mkString

  def rawChunks(observations: Vector[YamlChunkObservation]): String =
    observations.map { observation =>
      record(
        Vector(
          observation.name,
          observation.splitOffsets.mkString(","),
          option(observation.parseSnapshotSha256),
          option(observation.reconstructedSourceSha256),
          rawStrings(observation.reconstructionProblems),
          rawStrings(observation.axisErrors),
        )
      )
    }.mkString

  def digest(values: Vector[String]): String =
    YamlCorpusSha256.digestUtf8(rawStrings(values))

  def optionDigest(value: Option[String]): String =
    digest(Vector(option(value)))

  def eventsSha256(events: Vector[YamlNormalizedEvent]): String =
    YamlCorpusSha256.digestUtf8(rawEvents(events))

  def diagnosticsSha256(diagnostics: Vector[YamlDiagnosticKey]): String =
    YamlCorpusSha256.digestUtf8(rawDiagnostics(diagnostics))

  def position(value: SourcePosition): String =
    s"${value.offset}:${value.line}:${value.column}"

  def span(value: SourceSpan): String =
    record(Vector(value.source.value, position(value.start), position(value.end)))

private enum YamlSnapshotStep:
  case Visit(role: Option[String], node: UniNode)
  case CloseBranch

private[yaml] object YamlCorpusSnapshot:
  def sha256(parsed: ParseResult): String =
    val canonical = new StringBuilder
    canonical.append(
      YamlCorpusCanonical.record(
        Vector("parse", parsed.status.toString, parsed.roots.size.toString)
      )
    )
    parsed.diagnostics.foreach { diagnostic =>
      val details = diagnostic.details.map { pair =>
        YamlCorpusCanonical.record(Vector(pair._1, pair._2))
      }.mkString
      canonical.append(
        YamlCorpusCanonical.record(
          Vector(
            "diagnostic",
            diagnostic.severity.toString,
            diagnostic.code,
            YamlCorpusCanonical.option(
              diagnostic.span.map(YamlCorpusCanonical.span)
            ),
            YamlCorpusCanonical.option(diagnostic.dialect),
            YamlCorpusSha256.digestUtf8(details),
          )
        )
      )
    }

    var pending =
      parsed.roots.reverseIterator
        .map(node => YamlSnapshotStep.Visit(None, node))
        .toList
        .reverse
    while pending.nonEmpty do
      pending.head match
        case YamlSnapshotStep.Visit(role, UniNode.Token(token)) =>
          canonical.append(
            YamlCorpusCanonical.record(
              Vector(
                "token",
                YamlCorpusCanonical.option(role),
                token.id.toString,
                token.kind,
                token.channel.toString,
                YamlCorpusCanonical.span(token.span),
                token.lexeme.length.toString,
                Unicode.codePointCount(token.lexeme).toString,
                YamlCorpusSha256.digestUtf8(token.lexeme),
              )
            )
          )
          pending = pending.tail
        case YamlSnapshotStep.Visit(role, UniNode.Branch(kind, edges, span, origin)) =>
          val originFields = origin match
            case Origin.SourceBacked      => Vector("source-backed", "")
            case Origin.Synthetic(reason) => Vector("synthetic", reason)
          canonical.append(
            YamlCorpusCanonical.record(
              Vector(
                "branch-open",
                YamlCorpusCanonical.option(role),
                kind,
                edges.size.toString,
                YamlCorpusCanonical.span(span),
              ) ++ originFields
            )
          )
          val children =
            edges.map(edge => YamlSnapshotStep.Visit(edge.role, edge.child)).toList
          pending = children ::: YamlSnapshotStep.CloseBranch :: pending.tail
        case YamlSnapshotStep.CloseBranch =>
          canonical.append(YamlCorpusCanonical.record(Vector("branch-close")))
          pending = pending.tail
    YamlCorpusSha256.digestUtf8(canonical.result())

private enum YamlReconstructionStep:
  case Visit(node: UniNode)
  case FinishBranch(
      index: Int,
      kind: String,
      childCount: Int,
      span: SourceSpan,
      origin: Origin,
  )

private final case class YamlNodeSummary(
    span: SourceSpan,
    firstToken: Option[SourceSpan],
    lastToken: Option[SourceSpan],
)

private[yaml] object YamlCorpusReconstruction:
  def inspect(
      parsed: ParseResult,
      expectedSource: SourceId,
      expectedText: String,
  ): YamlReconstruction =
    val text = new StringBuilder
    val problems = Vector.newBuilder[String]
    val expectedEnd = Unicode.advance(SourcePosition.Start, expectedText)
    val sourcePositions =
      val result = Vector.newBuilder[SourcePosition]
      var position = SourcePosition.Start
      var utf16Index = 0
      result += position
      while utf16Index < expectedText.length do
        val first = expectedText.charAt(utf16Index)
        val width =
          if
            first >= '\uD800' && first <= '\uDBFF' &&
            utf16Index + 1 < expectedText.length &&
            expectedText.charAt(utf16Index + 1) >= '\uDC00' &&
            expectedText.charAt(utf16Index + 1) <= '\uDFFF'
          then 2
          else 1
        position = Unicode.advance(position, expectedText.substring(utf16Index, utf16Index + width))
        result += position
        utf16Index += width
      result.result()
    var pending = parsed.roots.map(YamlReconstructionStep.Visit.apply).toList
    var summaries: List[YamlNodeSummary] = Nil
    var expectedPosition = SourcePosition.Start
    var previousId: Option[Long] = None
    var seenIds: Set[Long] = Set.empty
    var tokenIndex = 0
    var branchIndex = 0

    def inspectPosition(label: String, position: SourcePosition): Unit =
      if position.offset < 0 then problems += s"$label offset is negative: ${position.offset}"
      if position.line < 1 then problems += s"$label line is not positive: ${position.line}"
      if position.column < 1 then problems += s"$label column is not positive: ${position.column}"
      if position.offset > expectedEnd.offset then
        problems += s"$label offset exceeds source end: ${position.offset} > ${expectedEnd.offset}"
      else if position.offset >= 0 && sourcePositions(position.offset) != position then
        problems +=
          s"$label is not the canonical position at offset ${position.offset}: " +
            s"expected=${sourcePositions(position.offset)} actual=$position"

    def inspectSpan(label: String, span: SourceSpan): Unit =
      if span.source != expectedSource then
        problems += s"$label source expected=$expectedSource actual=${span.source}"
      inspectPosition(s"$label start", span.start)
      inspectPosition(s"$label end", span.end)
      if span.start.offset > span.end.offset then
        problems += s"$label has reversed span start=${span.start} end=${span.end}"

    def contains(parent: SourceSpan, child: SourceSpan): Boolean =
      parent.source == child.source &&
        child.start.offset >= parent.start.offset &&
        child.end.offset <= parent.end.offset

    while pending.nonEmpty do
      pending.head match
        case YamlReconstructionStep.Visit(UniNode.Token(token)) =>
          inspectSpan(s"token[$tokenIndex]", token.span)
          if seenIds.contains(token.id) then
            problems += s"token[$tokenIndex] duplicate id=${token.id}"
          previousId.foreach { previous =>
            if token.id <= previous then
              problems += s"token[$tokenIndex] id=${token.id} is not strictly greater than $previous"
          }
          if token.span.start != expectedPosition then
            problems += s"token[$tokenIndex] gap/overlap expected-start=$expectedPosition actual=${token.span.start}"
          val advanced = Unicode.advance(token.span.start, token.lexeme)
          if token.span.end != advanced then
            problems += s"token[$tokenIndex] span end=${token.span.end} lexeme-end=$advanced"
          val width = Unicode.codePointCount(token.lexeme)
          if token.span.end.offset - token.span.start.offset != width then
            problems += s"token[$tokenIndex] code-point width=$width span-width=" +
              (token.span.end.offset - token.span.start.offset)
          text.append(token.lexeme)
          expectedPosition = token.span.end
          previousId = Some(token.id)
          seenIds += token.id
          tokenIndex += 1
          summaries = YamlNodeSummary(
            span = token.span,
            firstToken = Some(token.span),
            lastToken = Some(token.span),
          ) :: summaries
          pending = pending.tail
        case YamlReconstructionStep.Visit(UniNode.Branch(kind, edges, span, origin)) =>
          val current = branchIndex
          branchIndex += 1
          inspectSpan(s"branch[$current] kind=$kind", span)
          val children = edges.map(edge => YamlReconstructionStep.Visit(edge.child)).toList
          pending =
            children :::
              YamlReconstructionStep.FinishBranch(
                current,
                kind,
                edges.size,
                span,
                origin,
              ) :: pending.tail
        case YamlReconstructionStep.FinishBranch(index, kind, childCount, span, origin) =>
          val children = summaries.take(childCount).reverse
          summaries = summaries.drop(childCount)
          children.zipWithIndex.foreach { pair =>
            val child = pair._1
            if !contains(span, child.span) then
              problems +=
                s"branch[$index] kind=$kind does not contain child[${pair._2}] " +
                  s"branch-span=$span child-span=${child.span}"
          }
          val firstToken = children.flatMap(_.firstToken).headOption
          val lastToken = children.reverseIterator.flatMap(_.lastToken).take(1).toVector.headOption
          (firstToken, lastToken) match
            case (Some(first), Some(last)) =>
              if span.start != first.start || span.end != last.end then
                problems +=
                  s"branch[$index] kind=$kind span must equal descendant token bounds " +
                    s"expected=${SourceSpan(span.source, first.start, last.end)} actual=$span"
            case _ =>
              origin match
                case Origin.SourceBacked =>
                  problems += s"branch[$index] kind=$kind source-backed branch has no source token"
                case Origin.Synthetic(_) =>
                  if span.start != span.end then
                    problems +=
                      s"branch[$index] kind=$kind empty synthetic branch must be zero-width: $span"
          origin match
            case Origin.SourceBacked => ()
            case Origin.Synthetic(reason) =>
              if reason.trim.isEmpty then
                problems += s"branch[$index] kind=$kind synthetic origin has an empty reason"
          summaries = YamlNodeSummary(span, firstToken, lastToken) :: summaries
          pending = pending.tail

    if expectedPosition != expectedEnd then
      problems += s"final position expected=$expectedEnd actual=$expectedPosition"
    YamlReconstruction(text.result(), problems.result())

private[yaml] object YamlEventNormalization:
  def fromOfficial(text: String): Vector[YamlNormalizedEvent] =
    YamlCorpusText.lines(text).filter(_.nonEmpty).map(parseOfficialLine)

  def fromActual(stream: YamlValue.Stream): Vector[YamlNormalizedEvent] =
    val result = Vector.newBuilder[YamlNormalizedEvent]
    result += YamlNormalizedEvent("stream-start")
    stream.documents.foreach { document =>
      result += YamlNormalizedEvent("document-start")
      document.value.foreach(value => appendActual(value, result))
      result += YamlNormalizedEvent("document-end")
    }
    result += YamlNormalizedEvent("stream-end")
    result.result()

  private def parseOfficialLine(line: String): YamlNormalizedEvent =
    if line == "+STR" then YamlNormalizedEvent("stream-start")
    else if line == "-STR" then YamlNormalizedEvent("stream-end")
    else if line.startsWith("+DOC") then YamlNormalizedEvent("document-start")
    else if line.startsWith("-DOC") then YamlNormalizedEvent("document-end")
    else if line.startsWith("+MAP") then collection("mapping-start", line.drop(4))
    else if line == "-MAP" then YamlNormalizedEvent("mapping-end")
    else if line.startsWith("+SEQ") then collection("sequence-start", line.drop(4))
    else if line == "-SEQ" then YamlNormalizedEvent("sequence-end")
    else if line.startsWith("=ALI ") then
      YamlNormalizedEvent("alias", value = Some(line.drop(5).stripPrefix("*")))
    else if line.startsWith("=VAL ") then scalar(line.drop(5))
    else throw new IllegalArgumentException("unsupported yaml-test-suite event: " + line)

  private def collection(kind: String, source: String): YamlNormalizedEvent =
    val (anchor, tag, _) = properties(source, allowStyle = false)
    YamlNormalizedEvent(kind, anchor, tag)

  private def scalar(source: String): YamlNormalizedEvent =
    val (anchor, tag, remainder) = properties(source, allowStyle = true)
    if remainder.isEmpty then
      throw new IllegalArgumentException("yaml-test-suite scalar event has no style: " + source)
    val style = remainder.head
    if !":'\"|>".contains(style) then
      throw new IllegalArgumentException("unknown yaml-test-suite scalar style: " + source)
    YamlNormalizedEvent(
      "scalar",
      anchor,
      tag,
      Some(YamlCorpusText.unescapeEvent(remainder.drop(1))),
    )

  private def properties(
      source: String,
      allowStyle: Boolean,
  ): (Option[String], Option[String], String) =
    var rest = source.dropWhile(_ == ' ')
    if rest.startsWith("{}") || rest.startsWith("[]") then rest = rest.drop(2).dropWhile(_ == ' ')
    var anchor: Option[String] = None
    var tag: Option[String] = None
    var continue = true
    while continue && rest.nonEmpty do
      if rest.startsWith("&") then
        val end = rest.indexWhere(_.isWhitespace)
        val length = if end < 0 then rest.length else end
        anchor = Some(rest.slice(1, length))
        rest = rest.drop(length).dropWhile(_ == ' ')
      else if rest.startsWith("<") then
        val end = rest.indexOf('>')
        if end < 0 then throw new IllegalArgumentException("unterminated event tag: " + source)
        tag = Some(rest.slice(1, end))
        rest = rest.drop(end + 1).dropWhile(_ == ' ')
      else continue = false
    if !allowStyle && rest.nonEmpty then
      throw new IllegalArgumentException("unknown collection event properties: " + source)
    (anchor, tag, rest)

  private def appendActual(
      value: YamlValue,
      result: scala.collection.mutable.Builder[YamlNormalizedEvent, Vector[YamlNormalizedEvent]],
  ): Unit = value match
    case YamlValue.Stream(documents) =>
      fromActual(YamlValue.Stream(documents)).foreach(result += _)
    case YamlValue.Mapping(entries, tag, anchor) =>
      result += YamlNormalizedEvent("mapping-start", anchor, tag.map(normalizeActualTag))
      entries.foreach { entry =>
        appendActual(entry.key, result)
        appendActual(entry.value, result)
      }
      result += YamlNormalizedEvent("mapping-end")
    case YamlValue.Sequence(values, tag, anchor) =>
      result += YamlNormalizedEvent("sequence-start", anchor, tag.map(normalizeActualTag))
      values.foreach(value => appendActual(value, result))
      result += YamlNormalizedEvent("sequence-end")
    case YamlValue.Scalar(scalar, tag, anchor) =>
      val value = scalar match
        case YamlScalar.StringValue(cooked, _, _) => cooked
        case YamlScalar.NullValue(lexeme)         => lexeme
        case YamlScalar.BooleanValue(_, lexeme)   => lexeme
        case YamlScalar.IntegerValue(lexeme)      => lexeme
        case YamlScalar.FloatValue(lexeme)        => lexeme
      result += YamlNormalizedEvent("scalar", anchor, tag.map(normalizeActualTag), Some(value))
    case YamlValue.Alias(name) =>
      result += YamlNormalizedEvent("alias", value = Some(name))

  private def normalizeActualTag(tag: String): String =
    val expanded = tag match
      case "!!str"   => "tag:yaml.org,2002:str"
      case "!!null"  => "tag:yaml.org,2002:null"
      case "!!bool"  => "tag:yaml.org,2002:bool"
      case "!!int"   => "tag:yaml.org,2002:int"
      case "!!float" => "tag:yaml.org,2002:float"
      case value if value.startsWith("!<") && value.endsWith(">") =>
        value.substring(2, value.length - 1)
      case value => value
    YamlTagEnvironment.parserEventTag(expanded)

private[yaml] object YamlCorpusText:
  def lines(value: String): Vector[String] =
    value.split("\n", -1).toVector.map(_.stripSuffix("\r")).reverse.dropWhile(_.isEmpty).reverse

  def stripLineEnd(value: String): String =
    value.stripSuffix("\n").stripSuffix("\r")

  def unescapeEvent(value: String): String =
    val result = new StringBuilder
    var index = 0
    while index < value.length do
      if value.charAt(index) != '\\' || index + 1 >= value.length then
        result.append(value.charAt(index))
        index += 1
      else
        value.charAt(index + 1) match
          case '\\' => result.append('\\')
          case 'n'  => result.append('\n')
          case 'r'  => result.append('\r')
          case 't'  => result.append('\t')
          case 'b'  => result.append('\b')
          case other =>
            result.append('\\')
            result.append(other)
        index += 2
    result.result()

  def escape(value: String): String =
    val result = new StringBuilder("\"")
    value.foreach {
      case '\\' => result.append("\\\\")
      case '"'  => result.append("\\\"")
      case '\n' => result.append("\\n")
      case '\r' => result.append("\\r")
      case '\t' => result.append("\\t")
      case '\b' => result.append("\\b")
      case char if char >= '\uD800' && char <= '\uDFFF' =>
        result.append("\\u")
        result.append(f"${char.toInt}%04x")
      case char if char < ' ' =>
        result.append("\\u")
        result.append(f"${char.toInt}%04x")
      case char => result.append(char)
    }
    result.append('"').result()

  def utf16Hex(value: String): String =
    val result = new StringBuilder(value.length * 4)
    var index = 0
    while index < value.length do
      result.append(f"${value.charAt(index).toInt}%04x")
      index += 1
    result.result()

  def utf16Sha256(value: String): String =
    YamlCorpusSha256.digestAscii(utf16Hex(value))

  def canonicalText(value: String): String =
    if YamlCorpusUtf8.isWellFormed(value) then value
    else "invalid-utf16:" + utf16Hex(value)

  def scalaLiteral(value: String): String = escape(value)

private[yaml] object YamlCorpusDiff:
  def firstVectorDifference(
      expected: Vector[String],
      actual: Vector[String],
  ): String =
    val count = math.max(expected.size, actual.size)
    val index = (0 until count).find(position => expected.lift(position) != actual.lift(position))
    index match
      case None => "vectors are equal"
      case Some(position) =>
        s"first row difference index=$position expected=" +
          expected.lift(position).map(YamlCorpusText.escape).getOrElse("<missing>") +
          " actual=" +
          actual.lift(position).map(YamlCorpusText.escape).getOrElse("<missing>")

  def firstCharacterDifference(expected: String, actual: String): String =
    var expectedIndex = 0
    var actualIndex = 0
    var codePointOffset = 0
    while
      expectedIndex < expected.length &&
      actualIndex < actual.length &&
      codePointAt(expected, expectedIndex) == codePointAt(actual, actualIndex)
    do
      expectedIndex += codePointWidth(expected, expectedIndex)
      actualIndex += codePointWidth(actual, actualIndex)
      codePointOffset += 1
    if expectedIndex == expected.length && actualIndex == actual.length then "none"
    else
      val expectedStart = math.max(0, expectedIndex - 12)
      val actualStart = math.max(0, actualIndex - 12)
      val expectedEnd = math.min(expected.length, expectedIndex + 12)
      val actualEnd = math.min(actual.length, actualIndex + 12)
      s"code-point-offset=$codePointOffset expected-context=" +
        YamlCorpusText.escape(expected.substring(expectedStart, expectedEnd)) +
        " actual-context=" +
        YamlCorpusText.escape(actual.substring(actualStart, actualEnd))

  private def codePointAt(value: String, index: Int): Int =
    val first = value.charAt(index)
    if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length then
      val second = value.charAt(index + 1)
      if second >= '\uDC00' && second <= '\uDFFF' then
        0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00)
      else first.toInt
    else first.toInt

  private def codePointWidth(value: String, index: Int): Int =
    val first = value.charAt(index)
    if first >= '\uD800' && first <= '\uDBFF' && index + 1 < value.length &&
        value.charAt(index + 1) >= '\uDC00' && value.charAt(index + 1) <= '\uDFFF'
    then 2
    else 1

  def eventDifferences(
      expected: Vector[YamlNormalizedEvent],
      actual: Vector[YamlNormalizedEvent],
  ): Vector[String] =
    val count = math.max(expected.size, actual.size)
    (0 until count).flatMap { index =>
      val left = expected.lift(index)
      val right = actual.lift(index)
      if left == right then Vector.empty
      else
        Vector(
          f"@@ $index%03d @@",
          "- " + left.map(_.display).getOrElse("<missing>"),
          "+ " + right.map(_.display).getOrElse("<missing>"),
        )
    }.toVector

private[yaml] object YamlCorpusUtf8:
  def isWellFormed(value: String): Boolean =
    var index = 0
    var valid = true
    while index < value.length && valid do
      val first = value.charAt(index)
      if first >= '\uD800' && first <= '\uDBFF' then
        if
          index + 1 >= value.length ||
          value.charAt(index + 1) < '\uDC00' ||
          value.charAt(index + 1) > '\uDFFF'
        then valid = false
        else index += 2
      else if first >= '\uDC00' && first <= '\uDFFF' then valid = false
      else index += 1
    valid

  def encode(value: String): Array[Byte] =
    val bytes = Array.newBuilder[Byte]
    var index = 0
    while index < value.length do
      val first = value.charAt(index)
      val (codePoint, width) =
        if first >= '\uD800' && first <= '\uDBFF' then
          if index + 1 >= value.length then
            throw new IllegalArgumentException("unpaired UTF-16 high surrogate")
          val second = value.charAt(index + 1)
          if second < '\uDC00' || second > '\uDFFF' then
            throw new IllegalArgumentException("unpaired UTF-16 high surrogate")
          (0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00), 2)
        else if first >= '\uDC00' && first <= '\uDFFF' then
          throw new IllegalArgumentException("unpaired UTF-16 low surrogate")
        else (first.toInt, 1)
      if codePoint <= 0x7f then bytes += codePoint.toByte
      else if codePoint <= 0x7ff then
        bytes += (0xc0 | (codePoint >>> 6)).toByte
        bytes += (0x80 | (codePoint & 0x3f)).toByte
      else if codePoint <= 0xffff then
        bytes += (0xe0 | (codePoint >>> 12)).toByte
        bytes += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
        bytes += (0x80 | (codePoint & 0x3f)).toByte
      else
        bytes += (0xf0 | (codePoint >>> 18)).toByte
        bytes += (0x80 | ((codePoint >>> 12) & 0x3f)).toByte
        bytes += (0x80 | ((codePoint >>> 6) & 0x3f)).toByte
        bytes += (0x80 | (codePoint & 0x3f)).toByte
      index += width
    bytes.result()

  def decodeHex(value: String): String =
    if value.length % 2 != 0 then throw new IllegalArgumentException("odd-length UTF-8 hex")
    val bytes = new Array[Int](value.length / 2)
    var index = 0
    while index < bytes.length do
      bytes(index) = (hex(value.charAt(index * 2)) << 4) | hex(value.charAt(index * 2 + 1))
      index += 1
    decode(bytes)

  private def hex(char: Char): Int =
    if char >= '0' && char <= '9' then char - '0'
    else if char >= 'a' && char <= 'f' then char - 'a' + 10
    else if char >= 'A' && char <= 'F' then char - 'A' + 10
    else throw new IllegalArgumentException("invalid hex digit: " + char)

  private def decode(bytes: Array[Int]): String =
    val result = new StringBuilder
    var index = 0
    while index < bytes.length do
      val first = bytes(index)
      val (codePoint, width, minimum) =
        if first <= 0x7f then (first, 1, 0)
        else if (first & 0xe0) == 0xc0 then (first & 0x1f, 2, 0x80)
        else if (first & 0xf0) == 0xe0 then (first & 0x0f, 3, 0x800)
        else if (first & 0xf8) == 0xf0 then (first & 0x07, 4, 0x10000)
        else throw new IllegalArgumentException(f"invalid UTF-8 lead byte 0x$first%02x")
      if index + width > bytes.length then throw new IllegalArgumentException("truncated UTF-8")
      var value = codePoint
      var continuation = 1
      while continuation < width do
        val byte = bytes(index + continuation)
        if (byte & 0xc0) != 0x80 then
          throw new IllegalArgumentException(f"invalid UTF-8 continuation byte 0x$byte%02x")
        value = (value << 6) | (byte & 0x3f)
        continuation += 1
      if value < minimum || value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff) then
        throw new IllegalArgumentException(f"invalid UTF-8 code point U+$value%04X")
      if value <= 0xffff then result.append(value.toChar)
      else
        val rest = value - 0x10000
        result.append((0xd800 | (rest >>> 10)).toChar)
        result.append((0xdc00 | (rest & 0x3ff)).toChar)
      index += width
    result.result()

private[yaml] object YamlCorpusSha256:
  private val K: Array[Int] = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  )
  private val H0: Array[Int] = Array(
    0x6a09e667,
    0xbb67ae85,
    0x3c6ef372,
    0xa54ff53a,
    0x510e527f,
    0x9b05688c,
    0x1f83d9ab,
    0x5be0cd19,
  )

  def digestAscii(value: String): String =
    val bytes = new Array[Byte](value.length)
    var index = 0
    while index < value.length do
      val char = value.charAt(index)
      if char > 0x7f then throw new IllegalArgumentException("SHA-256 input must be ASCII")
      bytes(index) = char.toByte
      index += 1
    hexDigest(bytes)

  def digestUtf8(value: String): String =
    hexDigest(YamlCorpusUtf8.encode(value))

  private def hexDigest(bytes: Array[Byte]): String =
    digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  private inline def rotateRight(value: Int, distance: Int): Int =
    (value >>> distance) | (value << (32 - distance))

  private def digest(message: Array[Byte]): Array[Byte] =
    val padding = ((56 - (message.length + 1) % 64) + 64) % 64
    val total = message.length + 1 + padding + 8
    val buffer = new Array[Byte](total)
    var copied = 0
    while copied < message.length do
      buffer(copied) = message(copied)
      copied += 1
    buffer(message.length) = 0x80.toByte
    val bitLength = message.length.toLong * 8
    var index = 0
    while index < 8 do
      buffer(total - 1 - index) = ((bitLength >>> (8 * index)) & 0xffL).toByte
      index += 1

    val hash = H0.clone()
    val words = new Array[Int](64)
    var offset = 0
    while offset < total do
      var round = 0
      while round < 16 do
        words(round) =
          ((buffer(offset + round * 4) & 0xff) << 24) |
            ((buffer(offset + round * 4 + 1) & 0xff) << 16) |
            ((buffer(offset + round * 4 + 2) & 0xff) << 8) |
            (buffer(offset + round * 4 + 3) & 0xff)
        round += 1
      while round < 64 do
        val first = rotateRight(words(round - 15), 7) ^
          rotateRight(words(round - 15), 18) ^ (words(round - 15) >>> 3)
        val second = rotateRight(words(round - 2), 17) ^
          rotateRight(words(round - 2), 19) ^ (words(round - 2) >>> 10)
        words(round) = words(round - 16) + first + words(round - 7) + second
        round += 1

      var a = hash(0)
      var b = hash(1)
      var c = hash(2)
      var d = hash(3)
      var e = hash(4)
      var f = hash(5)
      var g = hash(6)
      var h = hash(7)
      round = 0
      while round < 64 do
        val sigmaOne = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
        val choice = (e & f) ^ (~e & g)
        val first = h + sigmaOne + choice + K(round) + words(round)
        val sigmaZero = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
        val majority = (a & b) ^ (a & c) ^ (b & c)
        val second = sigmaZero + majority
        h = g
        g = f
        f = e
        e = d + first
        d = c
        c = b
        b = a
        a = first + second
        round += 1
      hash(0) += a
      hash(1) += b
      hash(2) += c
      hash(3) += d
      hash(4) += e
      hash(5) += f
      hash(6) += g
      hash(7) += h
      offset += 64

    val output = new Array[Byte](32)
    index = 0
    while index < 8 do
      output(index * 4) = ((hash(index) >>> 24) & 0xff).toByte
      output(index * 4 + 1) = ((hash(index) >>> 16) & 0xff).toByte
      output(index * 4 + 2) = ((hash(index) >>> 8) & 0xff).toByte
      output(index * 4 + 3) = (hash(index) & 0xff).toByte
      index += 1
    output
