package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class YamlOfficialCorpusSpec extends AnyFunSuite:
  private lazy val report = YamlOfficialCorpusGate.evaluate()

  test("pinned data, source metadata, license, categories, and SHA-256 constants are exact") {
    assert(YamlOfficialCorpusData.version == "data-2022-01-17")
    assert(YamlOfficialCorpusData.revision == "6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f")
    assert(YamlOfficialCorpusData.dataTagObject == "5f49729577242103ae23838ac2ad4d9145aec126")
    assert(YamlOfficialCorpusData.archiveSha256 == "cc4a08f9ccc1cb2e66f32b3f1192bf1f07b3175682bbb715bf709bafa70322d4")
    assert(YamlOfficialCorpusData.sourceTag == "v2022-01-17")
    assert(YamlOfficialCorpusData.sourceTagObject == "6d48918a2320e767f6e2e57304f5ab42c19d71db")
    assert(YamlOfficialCorpusData.sourceRevision == "45db50aecf9b1520f8258938c88f396e96f30831")
    assert(YamlOfficialCorpusData.sourceTree == "1b1a150cd127094828f120e3d4c1cbefef42f02a")
    assert(YamlOfficialCorpusData.tagsTree == "a971feec6d8c46ba38db47c4751c3366270157e1")
    assert(YamlOfficialCorpusData.licenseBlob == "5059e95ab21ff74438dd5af5a3f50a1a62c4b05f")
    assert(YamlOfficialCorpusData.licenseSha256 == "c9562189164244554a69ab3f29d2d93ed9492c165723aaaa5fffc932cdbbfc85")
    assert(YamlOfficialCorpusData.categoriesSha256 == "cfbc81ae960db00e42822576d96ddeab2a6511da31b7e9a5e7e9f65b5474b755")
    assert(YamlOfficialCorpusData.treeManifestSha256 == "51c3212589a9c51ecb5de32ea9037be2c5a5aa38e2a6e710afa60f427403bf45")

    assert(YamlOfficialCorpus.cases.size == YamlOfficialCorpusData.expectedCaseCount)
    assert(YamlOfficialCorpus.cases.count(_.shouldFail) == YamlOfficialCorpusData.expectedErrorCount)
    val ids = YamlOfficialCorpus.cases.map(_.id)
    assert(ids == ids.sorted)
    assert(ids.distinct.size == ids.size)
    assert(ids.exists(_.contains("/")), "nested upstream case ids must remain distinct")
    assert(YamlOfficialCorpus.cases.forall(_.expectedEvents.nonEmpty))
    assert(YamlOfficialCorpus.cases.forall(_.categories.nonEmpty))
    assert(YamlOfficialCorpus.cases.flatMap(_.categories).distinct.size == 33)
    assert(YamlOfficialCorpus.canonicalCategories.linesIterator.size == 1150)
    assert(caseById("229Q").categories == Vector("mapping", "sequence", "spec"))
    assert(caseById("SM9W/00").categories == Vector("sequence"))
    assert(caseById("SM9W/01").categories == Vector("mapping"))

    assert(YamlCorpusSha256.digestAscii("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    assert(YamlCorpusSha256.digestAscii("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assert(YamlCorpusSha256.digestUtf8("😀") == "f0443a342c5ef54783a111b51ba56c938e474c32324d90c3a60c9c8e3a37e2d9")
    assert(
      YamlCorpusSha256.digestAscii(YamlOfficialCorpus.canonicalEncodedData) ==
        YamlOfficialCorpusData.logicalSha256
    )
    assert(
      YamlCorpusSha256.digestAscii(YamlOfficialCorpus.canonicalCategories) ==
        YamlOfficialCorpusData.categoriesSha256
    )
    assert(YamlOfficialCorpusIntegrity.pinnedProblems.isEmpty)
  }

  test("frozen baseline compares every expected and actual observable for all 402 cases") {
    assert(report.outcomes.size == 402)
    assert(report.outcomes.map(_.testCase.id) == YamlOfficialCorpus.cases.map(_.id))
    assert(report.census == YamlOfficialCorpusBaseline.census)
    assert(report.baselineRows.size == 402)
    assert(report.baselineRows == YamlOfficialCorpusBaseline.baselineRows)
    assert(report.baselineDigest == YamlOfficialCorpusBaseline.baselineSha256)
    assert(report.categoryRows == YamlOfficialCorpusBaseline.categoryRows)
    assert(report.categoryDigest == YamlOfficialCorpusBaseline.categorySha256)
    assert(report.categoryRows.size == 33)
    assert(!report.categoryRows.exists(_.startsWith("family=")))
    assert(!report.isStrictGreen)
    assert(report.failures.size == 290)
    YamlOfficialCorpusGate.requireCensus(report)
  }

  test("same aggregate census with a different diagnostic is rejected") {
    val passing = report.outcomes.find(_.strictExact).getOrElse(fail("baseline has no passing case"))
    val changed = passing.copy(
      diagnostics = passing.diagnostics :+ YamlDiagnosticKey("Warning", "deliberate-drift")
    )
    val changedReport = replace(report, changed)
    assert(changedReport.census == report.census)
    assert(changedReport.baselineRows != report.baselineRows)
    assert(changedReport.baselineDigest != report.baselineDigest)
    val error = intercept[IllegalStateException] {
      YamlOfficialCorpusGate.requireCensus(changedReport)
    }
    assert(error.getMessage.contains("baseline SHA-256"))
    assert(error.getMessage.contains("first row difference"))
  }

  test("digest, roster, categories, and stale-exclusion corruption all fail closed") {
    val encoded = YamlOfficialCorpusData.cases
    def inspect(
        values: Vector[YamlOfficialCaseData] = encoded,
        logicalSha256: String = YamlOfficialCorpusData.logicalSha256,
        categoriesSha256: String = YamlOfficialCorpusData.categoriesSha256,
        exclusions: Vector[String] = Vector.empty,
    ): Vector[String] =
      YamlOfficialCorpusIntegrity.problems(
        values,
        YamlOfficialCorpusData.expectedCaseCount,
        YamlOfficialCorpusData.expectedErrorCount,
        logicalSha256,
        categoriesSha256,
        exclusions,
      )

    assert(inspect(logicalSha256 = "0" * 64).exists(_.startsWith("logical SHA-256")))
    val corruptRoster = inspect(values = encoded.dropRight(1))
    assert(corruptRoster.exists(_.startsWith("case count")))
    assert(corruptRoster.exists(_.startsWith("logical SHA-256")))
    assert(corruptRoster.exists(_.startsWith("categories SHA-256")))
    assert(inspect(categoriesSha256 = "0" * 64).exists(_.startsWith("categories SHA-256")))
    val changedCategories = encoded.updated(
      0,
      encoded.head.copy(categories = encoded.head.categories :+ "aardvark-deliberate"),
    )
    val categoryProblems = inspect(values = changedCategories)
    assert(categoryProblems.exists(_.contains("categories are not sorted and unique")))
    assert(categoryProblems.exists(_.startsWith("categories SHA-256")))
    assert(inspect(exclusions = Vector("229Q")).exists(_.startsWith("stale encoding exclusions")))
  }

  test("bounded chunk schedules cover every case without changing its bytes") {
    YamlOfficialCorpus.cases.foreach { testCase =>
      val schedules = YamlCorpusChunks.schedules(testCase.input)
      assert(schedules.nonEmpty)
      assert(schedules.size <= 6, testCase.id)
      schedules.foreach { schedule =>
        assert(schedule.chunks.nonEmpty, s"${testCase.id}/${schedule.name}")
        assert(schedule.chunks.size <= 4, s"${testCase.id}/${schedule.name}")
        assert(schedule.chunks.mkString == testCase.input, s"${testCase.id}/${schedule.name}")
      }
      val crlf = testCase.input.indexOf("\r\n")
      if crlf >= 0 then
        val required = Vector(crlf, crlf + 1, crlf + 2)
        assert(
          schedules.exists(schedule => required.forall(YamlCorpusChunks.splitOffsets(schedule).contains)),
          s"${testCase.id} has no before/between/after CRLF schedule",
        )
      firstSurrogatePair(testCase.input).foreach { start =>
        val required = Vector(start, start + 1, start + 2)
        assert(
          schedules.exists(schedule => required.forall(YamlCorpusChunks.splitOffsets(schedule).contains)),
          s"${testCase.id} has no before/between/after astral schedule",
        )
      }
    }
  }

  test("semantic StackOverflowError preserves source and chunk axes and all 402 cases are attempted") {
    val crashId = YamlOfficialCorpus.cases(YamlOfficialCorpus.cases.size / 2).id
    var attempted = Vector.empty[String]
    val hooks = YamlCorpusHooks.default.copy(
      semantic = (source, input) =>
        val id = source.value.stripPrefix("yaml-test-suite:")
        attempted = attempted :+ id
        if id == crashId then throw new StackOverflowError("deliberate semantic crash")
        YamlCorpusHooks.default.semantic(source, input)
    )
    val crashedReport = YamlOfficialCorpusGate.evaluate(hooks)
    assert(attempted == YamlOfficialCorpus.cases.map(_.id))
    assert(crashedReport.outcomes.size == 402)
    assert(crashedReport.census.crashes == 1)
    val crashed = crashedReport.outcomes.find(_.testCase.id == crashId).get
    assert(crashed.sourceExact)
    assert(crashed.chunkExact)
    assert(crashed.semanticAxisError.exists(_.contains("deliberate semantic crash")))
    assert(!crashed.validityObserved)
    assert(!crashed.semanticsExact)
    assert(!crashed.strictExact)
  }

  test("one crashing chunk schedule preserves whole-source and semantic observations") {
    val testCase = YamlOfficialCorpus.cases.find(value =>
      YamlCorpusChunks.schedules(value.input).exists(_.chunks.size > 1)
    ).get
    var attempts = 0
    val hooks = YamlCorpusHooks.default.copy(
      parse = source =>
        attempts += 1
        if source.chunks.size > 1 then throw new StackOverflowError("deliberate chunk crash")
        YamlCorpusHooks.default.parse(source)
    )
    val outcome = YamlOfficialCorpusGate.evaluateOne(testCase, hooks)
    assert(attempts == YamlCorpusChunks.schedules(testCase.input).size)
    assert(outcome.sourceExact)
    assert(!outcome.chunkExact)
    assert(outcome.chunkObservations.exists(_.axisErrors.exists(_.contains("deliberate chunk crash"))))
    assert(outcome.semanticAxisError.isEmpty)
    assert(outcome.actualEvents.nonEmpty)
    assert(outcome.crash.nonEmpty)
  }

  test("outer case failure is isolated but fatal VM errors are not swallowed") {
    val crashId = YamlOfficialCorpus.cases(YamlOfficialCorpus.cases.size / 2).id
    val baselineById = report.outcomes.map(value => value.testCase.id -> value).toMap
    var attempted = Vector.empty[String]
    val crashedReport = YamlOfficialCorpusGate.evaluateCases(YamlOfficialCorpus.cases) { testCase =>
      attempted = attempted :+ testCase.id
      if testCase.id == crashId then throw new IllegalStateException("deliberate outer crash")
      baselineById(testCase.id)
    }
    assert(attempted == YamlOfficialCorpus.cases.map(_.id))
    assert(crashedReport.census.crashes == 1)
    assert(
      crashedReport.outcomes.find(_.testCase.id == crashId).flatMap(_.crash)
        .exists(_.contains("deliberate outer crash"))
    )
    assertThrows[LinkageError] {
      YamlOfficialCorpusGate.evaluateCases(Vector(YamlOfficialCorpus.cases.head)) { _ =>
        throw new LinkageError("must propagate")
      }
    }
  }

  test("a truncated all-passing roster cannot green either public gate") {
    val passing = report.outcomes.find(_.strictExact).getOrElse(fail("baseline has no passing case"))
    val truncated = YamlOfficialCorpusGate.report(Vector(passing))
    assert(truncated.isStrictGreen, "the strict predicate alone intentionally has no roster context")
    val strictError = intercept[IllegalStateException] {
      YamlOfficialCorpusGate.requireStrict(truncated)
    }
    assert(strictError.getMessage.contains("case roster expected=402 actual=1"))
    assertThrows[IllegalStateException](YamlOfficialCorpusGate.requireCensus(truncated))
    assertThrows[IllegalStateException](YamlOfficialCorpusGate.requireIntegrityAndRoster(truncated))
  }

  test("a corrupted expectation prints both sides and is rejected on the full roster") {
    val passing = report.outcomes.find(_.strictExact).getOrElse(fail("baseline has no strict passing case"))
    val corrupted = passing.testCase.copy(
      expectedEvents = passing.testCase.expectedEvents :+ YamlNormalizedEvent("corrupted-expectation")
    )
    val outcome = YamlOfficialCorpusGate.evaluateOne(corrupted)
    assert(outcome.sourceExact)
    assert(outcome.expectedValid == outcome.actualValid)
    assert(!outcome.semanticsExact)
    assert(!outcome.strictExact)

    val corruptedReport = replace(report, outcome)
    val rendered = YamlOfficialCorpusGate.renderFailure(outcome)
    assert(rendered.contains("CASE " + passing.testCase.id))
    assert(rendered.contains("events expected:"))
    assert(rendered.contains("events actual:"))
    assert(rendered.contains("events diff:"))
    assertThrows[IllegalStateException](YamlOfficialCorpusGate.requireStrict(corruptedReport))
    assertThrows[IllegalStateException](YamlOfficialCorpusGate.requireCensus(corruptedReport))
  }

  test("reconstruction validates branch containment, bounds, and synthetic-origin contract") {
    val source = SourceId("reconstruction-test")
    val span = SourceSpan(source, SourcePosition.Start, SourcePosition(1, 1, 2))
    val token = SourceToken(0L, "test", "a", span)
    val synthetic = UniNode.Branch(
      "synthetic",
      Vector(UniEdge(None, UniNode.Token(token))),
      span,
      Origin.Synthetic("recovery"),
    )
    val valid = YamlCorpusReconstruction.inspect(
      ParseResult(Vector(synthetic), Vector.empty, CompletionStatus.Complete),
      source,
      "a",
    )
    assert(valid.text == "a")
    assert(valid.problems.isEmpty)

    val tooSmall = UniNode.Branch(
      "too-small",
      Vector(UniEdge(None, UniNode.Token(token))),
      SourceSpan(source, SourcePosition.Start, SourcePosition.Start),
      Origin.SourceBacked,
    )
    val tooSmallProblems = inspectTree(source, "a", tooSmall)
    assert(tooSmallProblems.exists(_.contains("does not contain child")))
    assert(tooSmallProblems.exists(_.contains("span must equal descendant token bounds")))

    val emptySourceBacked = UniNode.Branch(
      "empty-source",
      Vector.empty,
      SourceSpan(source, SourcePosition.Start, SourcePosition.Start),
      Origin.SourceBacked,
    )
    assert(inspectTree(source, "", emptySourceBacked).exists(_.contains("has no source token")))

    val invalidSynthetic = UniNode.Branch(
      "invalid-synthetic",
      Vector.empty,
      span,
      Origin.Synthetic(""),
    )
    val syntheticProblems = inspectTree(source, "", invalidSynthetic)
    assert(syntheticProblems.exists(_.contains("must be zero-width")))
    assert(syntheticProblems.exists(_.contains("empty reason")))
  }

  test("event normalization removes presentation style but retains semantic properties") {
    val block = YamlEventNormalization.fromOfficial(
      "+STR\n+DOC ---\n+MAP {}\n=VAL &name <tag:yaml.org,2002:str> \"a\\nb\n-MAP\n-DOC ...\n-STR\n"
    )
    val presentationVariant = YamlEventNormalization.fromOfficial(
      "+STR\n+DOC\n+MAP\n=VAL &name <tag:yaml.org,2002:str> |a\\nb\n-MAP\n-DOC\n-STR\n"
    )
    assert(block == presentationVariant)
    assert(
      block.contains(
        YamlNormalizedEvent(
          kind = "scalar",
          anchor = Some("name"),
          tag = Some("tag:yaml.org,2002:str"),
          value = Some("a\nb"),
        )
      )
    )
  }

  test("source diffs report code-point rather than UTF-16 offsets") {
    val rendered = YamlCorpusDiff.firstCharacterDifference("😀a", "😀b")
    assert(rendered.contains("code-point-offset=1"))
    assert(!rendered.contains("utf16"))
  }

  private def caseById(id: String): YamlOfficialCase =
    YamlOfficialCorpus.cases.find(_.id == id).get

  private def replace(
      original: YamlCorpusReport,
      replacement: YamlCorpusOutcome,
  ): YamlCorpusReport =
    YamlOfficialCorpusGate.report(
      original.outcomes.map { outcome =>
        if outcome.testCase.id == replacement.testCase.id then replacement else outcome
      }
    )

  private def inspectTree(source: SourceId, text: String, root: UniNode): Vector[String] =
    YamlCorpusReconstruction
      .inspect(ParseResult(Vector(root), Vector.empty, CompletionStatus.Complete), source, text)
      .problems

  private def firstSurrogatePair(value: String): Option[Int] =
    var index = 0
    var result: Option[Int] = None
    while index + 1 < value.length && result.isEmpty do
      val first = value.charAt(index)
      val second = value.charAt(index + 1)
      if first >= '\uD800' && first <= '\uDBFF' && second >= '\uDC00' && second <= '\uDFFF' then
        result = Some(index)
      else index += 1
    result
