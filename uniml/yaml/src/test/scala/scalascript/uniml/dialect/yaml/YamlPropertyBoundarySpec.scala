package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class YamlPropertyBoundarySpec extends AnyFunSuite:
  private val source = SourceId("memory:yaml-property-boundary")

  test("opening flow indicators never replace separation after a property") {
    Vector(
      "!foo[x]\n" -> "uniml.yaml.invalid-tag",
      "!{x: y}\n" -> "uniml.yaml.invalid-tag",
      "!foo{x}\n" -> "uniml.yaml.invalid-tag",
      "&a[x]\n" -> "uniml.yaml.invalid-anchor",
      "key: [\n  !foo{x}\n]\n" -> "uniml.yaml.invalid-tag",
    ).foreach { pair =>
      val result = parse(pair._1)
      val errors = result.diagnostics.filter(_.severity == Severity.Error)
      assert(errors.map(_.code) == Vector(pair._2), s"${pair._1}: $errors")
      assert(Yaml.project(result).value.isEmpty)
      assert(sourceText(result) == pair._1)
    }
  }

  test("comma and closing flow indicators terminate property-only empty nodes only in flow") {
    val valid = Vector(
      "[!!str, value]\n",
      "[!, value]\n",
      "[!]\n",
      "{key: !}\n",
    )
    valid.foreach { text =>
      val result = parse(text)
      assert(!result.diagnostics.exists(_.severity == Severity.Error), s"$text: ${result.diagnostics}")
      val projection = Yaml.project(result)
      assert(projection.value.nonEmpty, s"$text: ${projection.diagnostics}")
    }

    val invalid = parse("- !!str, xxx\n")
    assert(invalid.diagnostics.count(_.code == "uniml.yaml.invalid-tag") == 1, invalid.diagnostics)
    assert(Yaml.project(invalid).value.isEmpty)
  }

  test("tag characters and effective global URIs fail closed") {
    val unicode = parse("!café value\n")
    assert(unicode.diagnostics.map(_.code) == Vector("uniml.yaml.invalid-tag"))
    assert(Yaml.project(unicode).value.isEmpty)

    Vector(
      "!<relative> value\n" -> "uniml.yaml.invalid-tag",
      "%TAG !e! relative\n---\n!e!suffix value\n" -> "uniml.yaml.invalid-tag",
    ).foreach { pair =>
      val parsed = parse(pair._1)
      assert(!parsed.diagnostics.exists(_.severity == Severity.Error), parsed.diagnostics)
      val projection = Yaml.project(parsed)
      assert(projection.value.isEmpty)
      assert(projection.diagnostics.map(_.code).contains(pair._2), projection.diagnostics)
    }
  }

  test("aliases are complete nodes and colon remains part of property names") {
    Vector("*a trailing\n", "*a !tag\n", "*a &b\n").foreach { text =>
      val projection = Yaml.project(parse(text))
      assert(projection.value.isEmpty, text)
      assert(projection.diagnostics.exists(_.code == "uniml.yaml.invalid-alias"), projection.diagnostics)
    }

    val text = "&a: key: &a value\nfoo:\n  *a:\n"
    val projection = Yaml.project(parse(text))
    assert(!projection.diagnostics.exists(_.severity == Severity.Error), projection.diagnostics)
    val stream = projection.value.get.asInstanceOf[YamlValue.Stream]
    val mapping = stream.documents.head.value.get.asInstanceOf[YamlValue.Mapping]
    val firstKey = mapping.entries.head.key.asInstanceOf[YamlValue.Scalar]
    assert(firstKey.anchor.contains("a:"))
    assert(firstKey.value == YamlScalar.StringValue("key", "key", ScalarStyle.Plain))
    val nested = mapping.entries(1).value.asInstanceOf[YamlValue.Alias]
    assert(nested.name == "a:")
  }

  test("property indicators inside an active plain scalar remain scalar content") {
    Vector(
      "key: text !café\n",
      "- foo !bad{} bar\n",
      "foo !bad% bar\n",
      "key: text &\n",
      "key: text *\n",
      "[text !café]\n",
      "key: foo, !café\n",
      "key: foo [bar] !café\n",
      "key: foo \"bar\" !café\n",
      "key: foo - !café\n",
    ).foreach { text =>
      val parsed = parse(text)
      assert(!parsed.diagnostics.exists(_.severity == Severity.Error), s"$text: ${parsed.diagnostics}")
      val projection = Yaml.project(parsed)
      assert(projection.value.nonEmpty, s"$text: ${projection.diagnostics}")
      val propertyTokens =
        parsed.roots
          .flatMap(UniNode.sourceTokens)
          .filter(token => token.kind == "yaml.tag" || token.kind == "yaml.anchor" || token.kind == "yaml.alias")
      assert(propertyTokens.isEmpty, s"$text: $propertyTokens")
      assert(sourceText(parsed) == text)
    }
  }

  test("property diagnostics resume at a following node") {
    Vector(
      "!café value\n",
      "[!café value]\n",
      "[value, !café value]\n",
      "key: \"value\"\n  !café x\n",
      "key: [value]\n  !bad{} x\n",
    ).foreach { text =>
      val parsed = parse(text)
      assert(parsed.diagnostics.exists(_.code == "uniml.yaml.invalid-tag"), s"$text: ${parsed.diagnostics}")
    }
  }

  test("comments, percent spelling, Unicode anchors, and every chunk split are invariant") {
    val text =
      "%TAG !e! tag:example.com,2000:%2f\n" +
        "---\n" +
        "root: &a😀: !e!suffix%2F value # comment\n" +
        "copy: *a😀:\n"
    val baseline = parse(text)
    assert(!baseline.diagnostics.exists(_.severity == Severity.Error), baseline.diagnostics)
    val baselineShape = fingerprint(baseline)
    val baselineProjection = Yaml.project(baseline)
    assert(baselineProjection.value.nonEmpty, baselineProjection.diagnostics)

    (0 to text.length).foreach { split =>
      val chunks = Vector(SourceChunk(text.take(split)), SourceChunk(text.drop(split)))
      val result = Yaml.parse(SourceInput(source, chunks))
      assert(fingerprint(result) == baselineShape, s"split=$split")
      assert(Yaml.project(result) == baselineProjection, s"projection split=$split")
    }
  }

  private def parse(text: String): ParseResult = Yaml.parse(SourceInput.fromString(source, text))

  private def sourceText(result: ParseResult): String =
    result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id).map(_.lexeme).mkString

  private def fingerprint(result: ParseResult): (
      CompletionStatus,
      Vector[(String, String, TokenChannel, SourceSpan)],
      Vector[(String, Severity, Option[SourceSpan])],
  ) =
    (
      result.status,
      result.roots
        .flatMap(UniNode.sourceTokens)
        .sortBy(_.id)
        .map(token => (token.kind, token.lexeme, token.channel, token.span)),
      result.diagnostics.map(value => (value.code, value.severity, value.span)),
    )
