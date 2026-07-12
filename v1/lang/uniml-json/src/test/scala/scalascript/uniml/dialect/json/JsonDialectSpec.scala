package scalascript.uniml.dialect.json

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class JsonDialectSpec extends AnyFunSuite:
  private val source = SourceId("memory:test.json")

  test("accepts every RFC 8259 root value form") {
    val valid = Vector(
      "{}",
      "[]",
      "\"text\"",
      "0",
      "-0",
      "12.50e-2",
      "true",
      "false",
      "null",
      " \t\r\n[1, {\"x\": false}, null] \n",
    )
    valid.foreach { text =>
      val result = parse(text)
      assert(result.status == CompletionStatus.Complete, s"$text: ${result.diagnostics}")
    }
  }

  test("builds balanced branches and preserves every spelling once") {
    val text = " \n{ \"a\" : [1, 2], \"a\": {\"x\":true} }\t"
    val result = parse(text)

    assert(result.status == CompletionStatus.Complete)
    val tokens = allTokens(result)
    assert(tokens.map(_.lexeme).mkString == text)
    assert(tokens.map(_.id) == tokens.indices.map(_.toLong).toVector)
    assert(tokens.count(_.kind == "json.whitespace") == 9)
    val objectRoot = result.roots.collectFirst { case branch @ UniNode.Branch("json.object", _, _, _) => branch }
    assert(objectRoot.nonEmpty)
    assert(objectRoot.toVector.flatMap(UniNode.sourceTokens).map(_.lexeme).mkString == text.trim)
  }

  test("is invariant across every UTF-16 chunk split including a surrogate pair") {
    val text = " {\"emoji\":\"😀\",\"escaped\":\"\\uD834\\uDD1E\",\"n\":-12.5E+3} "
    val baseline = parse(text)
    assert(baseline.status == CompletionStatus.Complete)

    (0 to text.length).foreach { split =>
      val chunked = Json.parse(SourceInput(source, Vector(
        SourceChunk(text.substring(0, split)),
        SourceChunk(text.substring(split)),
      )))
      assert(chunked == baseline, s"chunk split $split changed parse result")
    }
  }

  test("preserves exact valid number spellings") {
    val valid = Vector("0", "-0", "10", "-123", "0.0", "1.2500", "1e9", "1E+09", "-2.5e-7")
    valid.foreach { number =>
      val result = parse(number)
      assert(result.status == CompletionStatus.Complete, s"$number: ${result.diagnostics}")
      assert(allTokens(result).exists(token => token.kind == "json.number" && token.lexeme == number))
    }
  }

  test("rejects non-RFC numbers, literals, comments, quotes, and trailing data") {
    val invalid = Vector(
      "01" -> "uniml.json.invalid-number",
      "1." -> "uniml.json.invalid-number",
      "1e" -> "uniml.json.invalid-number",
      "1١" -> "uniml.json.invalid-number",
      "+1" -> "uniml.json.invalid-character",
      "NaN" -> "uniml.json.invalid-literal",
      "TRUE" -> "uniml.json.invalid-literal",
      "'x'" -> "uniml.json.invalid-character",
      "// comment\n1" -> "uniml.json.invalid-character",
      "true false" -> "uniml.json.trailing-data",
    )
    invalid.foreach { case (text, code) =>
      val result = parse(text)
      assert(result.status != CompletionStatus.Complete, s"unexpectedly accepted $text")
      assert(result.diagnostics.exists(_.code == code), s"$text: ${result.diagnostics.map(_.code)}")
    }
  }

  test("rejects trailing commas but retains balanced container trees") {
    Vector("[1,]", "{\"a\":1,}").foreach { text =>
      val result = parse(text)
      assert(result.status == CompletionStatus.Incomplete)
      assert(result.diagnostics.exists(_.code == "uniml.json.trailing-comma"))
      assert(allTokens(result).map(_.lexeme).mkString == text)
      assert(!result.diagnostics.exists(_.code == "uniml.vm.unclosed-node"))
    }
  }

  test("reports empty, truncated, and malformed strings without throwing") {
    val invalid = Vector("", " \n", "\"", "\"\\", "\"\\u12", "[1", "{\"a\":")
    invalid.foreach { text =>
      val result = parse(text)
      assert(result.status != CompletionStatus.Complete, s"unexpectedly accepted '$text'")
      assert(result.diagnostics.nonEmpty)
    }
  }

  test("preserves a leading BOM with a warning and rejects it elsewhere") {
    val leading = parse("\uFEFF{\"a\":1}")
    assert(leading.status == CompletionStatus.Complete)
    assert(leading.diagnostics.map(_.code) == Vector("uniml.json.bom"))
    assert(allTokens(leading).head.kind == "json.bom")

    val embedded = parse("[\uFEFF]")
    assert(embedded.status == CompletionStatus.Incomplete)
    assert(embedded.diagnostics.exists(_.code == "uniml.json.invalid-character"))
  }

  test("enforces JSON and core resource limits") {
    val sourceLimited = Json.parse(
      SourceInput.fromString(source, "true"),
      JsonLimits(maxSourceCodePoints = 2),
    )
    assert(sourceLimited.status == CompletionStatus.Halted)
    assert(sourceLimited.diagnostics.exists(_.code == "uniml.json.limit.source"))

    val numberLimited = Json.parse(
      SourceInput.fromString(source, "123"),
      JsonLimits(maxNumberCodePoints = 2),
    )
    assert(numberLimited.status == CompletionStatus.Halted)
    assert(numberLimited.diagnostics.exists(_.code == "uniml.json.limit.number"))

    val stringLimited = Json.parse(
      SourceInput.fromString(source, "\"abcd\""),
      JsonLimits(maxStringCodePoints = 3),
    )
    assert(stringLimited.status == CompletionStatus.Halted)
    assert(stringLimited.diagnostics.exists(_.code == "uniml.json.limit.string"))

    val nodeLimited = Json.parse(
      SourceInput.fromString(source, "{}"),
      JsonLimits(core = Limits(maxNodes = 1)),
    )
    assert(nodeLimited.status == CompletionStatus.Halted)
    assert(nodeLimited.diagnostics.exists(_.code == "uniml.limit.nodes"))

    val depthLimited = Json.parse(
      SourceInput.fromString(source, "[[[]]]"),
      JsonLimits(core = Limits(maxDepth = 2)),
    )
    assert(depthLimited.status == CompletionStatus.Halted)
    assert(depthLimited.diagnostics.exists(_.code == "uniml.limit.depth"))
  }

  private def parse(text: String): ParseResult = Json.parse(SourceInput.fromString(source, text))

  private def allTokens(result: ParseResult): Vector[SourceToken] =
    result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id)
