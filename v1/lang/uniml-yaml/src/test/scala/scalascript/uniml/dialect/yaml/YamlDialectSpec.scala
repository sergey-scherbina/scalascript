package scalascript.uniml.dialect.yaml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class YamlDialectSpec extends AnyFunSuite:
  private val source = SourceId("memory:yaml-test")

  test("block mappings and sequences are lossless and use Core Schema") {
    val text = "name: demo\nitems:\n  - true\n  - no\ncount: 0x10\n"
    val result = parse(text)

    assert(result.status == CompletionStatus.Complete)
    assert(result.diagnostics.isEmpty)
    assert(sourceText(result) == text)
    assert(branchKinds(result).contains("yaml.mapping"))
    assert(branchKinds(result).contains("yaml.sequence"))

    val stream = projected(result)
    val mapping = documentValue(stream).asInstanceOf[YamlValue.Mapping]
    assert(mapping.entries.map(scalarStringKey) == Vector("name", "items", "count"))
    val items = mapping.entries(1).value.asInstanceOf[YamlValue.Sequence]
    assert(items.values.head == YamlValue.Scalar(YamlScalar.BooleanValue(true, "true"), None, None))
    assert(items.values(1) == YamlValue.Scalar(YamlScalar.StringValue("no", "no", ScalarStyle.Plain), None, None))
    assert(mapping.entries(2).value == YamlValue.Scalar(YamlScalar.IntegerValue("0x10"), None, None))
  }

  test("tokenization, trees, and diagnostics are invariant at every two-chunk split") {
    val text = "---\r\nroot: {quoted: \"a\\nb\", list: [1, null, false]} # note\r\n"
    val baseline = parse(text)
    val baselineTokens = tokenShape(baseline)
    val baselineTree = branchKinds(baseline)
    val baselineDiagnostics = baseline.diagnostics.map(_.code)

    (0 to text.length).foreach { split =>
      val input = SourceInput(source, Vector(SourceChunk(text.take(split)), SourceChunk(text.drop(split))))
      val result = Yaml.parse(input)
      assert(tokenShape(result) == baselineTokens, s"token mismatch at UTF-16 split $split")
      assert(branchKinds(result) == baselineTree, s"tree mismatch at UTF-16 split $split")
      assert(result.diagnostics.map(_.code) == baselineDiagnostics, s"diagnostic mismatch at split $split")
      assert(sourceText(result) == text)
    }
  }

  test("flow mappings and sequences retain order and quoted scalar styles") {
    val result = parse("{first: [1, 'two', \"three\"], empty: {}}\n")
    assert(branchKinds(result).contains("yaml.mapping.flow"))
    assert(branchKinds(result).contains("yaml.sequence.flow"))

    val mapping = documentValue(projected(result)).asInstanceOf[YamlValue.Mapping]
    assert(mapping.entries.map(scalarStringKey) == Vector("first", "empty"))
    val values = mapping.entries.head.value.asInstanceOf[YamlValue.Sequence].values
    assert(values(1) == YamlValue.Scalar(YamlScalar.StringValue("two", "'two'", ScalarStyle.SingleQuoted), None, None))
    assert(values(2) == YamlValue.Scalar(YamlScalar.StringValue("three", "\"three\"", ScalarStyle.DoubleQuoted), None, None))
  }

  test("literal and folded block scalars implement indentation and chomping") {
    val text = "literal: |-\n  one\n  two\nfolded: >\n  alpha\n  beta\n"
    val mapping = documentValue(projected(parse(text))).asInstanceOf[YamlValue.Mapping]
    val literal = mapping.entries.head.value.asInstanceOf[YamlValue.Scalar].value
    val folded = mapping.entries(1).value.asInstanceOf[YamlValue.Scalar].value

    assert(literal == YamlScalar.StringValue("one\ntwo", "|-\n  one\n  two\n", ScalarStyle.Literal))
    assert(folded == YamlScalar.StringValue("alpha beta\n", ">\n  alpha\n  beta\n", ScalarStyle.Folded))
  }

  test("directives, explicit multi-document streams, comments, and markers are preserved") {
    val text = "%YAML 1.2\n---\na: 1 # first\n...\n---\n- x\n- y\n"
    val result = parse(text)
    assert(sourceText(result) == text)
    val stream = projected(result)
    assert(stream.documents.size == 2)
    assert(branchKinds(result).count(_ == "yaml.document") == 2)
    assert(stream.documents.head.directives.map(_.name) == Vector("YAML"))
    assert(stream.documents.head.directives.head.lexeme == "%YAML 1.2")
    assert(result.roots.flatMap(UniNode.sourceTokens).exists(_.kind == "yaml.comment"))
  }

  test("empty streams, explicit keys, empty values, and compact mappings remain distinct") {
    val empty = projected(parse(""))
    assert(empty.documents.isEmpty)

    val text = "? [a, b]\n: explicit\nempty:\nitems:\n  - name: first\n    enabled: true\n"
    val mapping = documentValue(projected(parse(text))).asInstanceOf[YamlValue.Mapping]
    assert(mapping.entries.head.key.isInstanceOf[YamlValue.Sequence])
    assert(mapping.entries(1).value == YamlValue.Scalar(YamlScalar.NullValue(""), None, None))
    val item = mapping.entries(2).value.asInstanceOf[YamlValue.Sequence].values.head.asInstanceOf[YamlValue.Mapping]
    assert(item.entries.map(scalarStringKey) == Vector("name", "enabled"))
  }

  test("all-feature transport remains invariant around properties and block scalars") {
    val text = "%TAG !e! tag:example.org,2026:\n---\nroot: &root\n  text: |2-\n    hello\n  flow: [!e!value 'x', *root]\n...\n"
    val baseline = parse(text)
    val shape = tokenShape(baseline)
    (0 to text.length).foreach { split =>
      val result = Yaml.parse(SourceInput(source, Vector(SourceChunk(text.take(split)), SourceChunk(text.drop(split)))))
      assert(tokenShape(result) == shape, s"all-feature token mismatch at split $split")
      assert(sourceText(result) == text)
      assert(result.diagnostics.map(_.code) == baseline.diagnostics.map(_.code))
    }
  }

  test("tags are inert and anchors and aliases are preserved by default") {
    val text = "base: &base !local [1, 2]\ncopy: *base\n"
    val stream = projected(parse(text))
    val mapping = documentValue(stream).asInstanceOf[YamlValue.Mapping]
    val base = mapping.entries.head.value.asInstanceOf[YamlValue.Sequence]
    assert(base.tag.contains("!local"))
    assert(base.anchor.contains("base"))
    assert(mapping.entries(1).value == YamlValue.Alias("base"))
  }

  test("explicit alias resolution clones bounded values") {
    val result = parse("base: &base [1, 2]\ncopy: *base\n")
    val projectedResult = Yaml.project(result, YamlProjectionOptions(aliases = AliasPolicy.Resolve))
    assert(projectedResult.diagnostics.isEmpty)
    val mapping = documentValue(projectedResult.value.get.asInstanceOf[YamlValue.Stream]).asInstanceOf[YamlValue.Mapping]
    assert(mapping.entries(1).value.isInstanceOf[YamlValue.Sequence])
  }

  test("property-only values attach anchors to following nested nodes") {
    val text = "root: &root\n  child: value\nlist:\n  - &item\n    name: demo\n"
    val mapping = documentValue(projected(parse(text))).asInstanceOf[YamlValue.Mapping]
    val root = mapping.entries.head.value.asInstanceOf[YamlValue.Mapping]
    assert(root.anchor.contains("root"))
    val item = mapping.entries(1).value.asInstanceOf[YamlValue.Sequence].values.head.asInstanceOf[YamlValue.Mapping]
    assert(item.anchor.contains("item"))
  }

  test("undefined and cyclic aliases fail explicitly") {
    val undefined = Yaml.project(parse("value: *missing\n"))
    assert(undefined.value.isEmpty)
    assert(undefined.diagnostics.exists(_.code == "uniml.yaml.undefined-alias"))

    val cyclic = Yaml.project(
      parse("value: &loop [*loop]\n"),
      YamlProjectionOptions(aliases = AliasPolicy.Resolve),
    )
    assert(cyclic.value.isEmpty)
    assert(cyclic.diagnostics.exists(_.code == "uniml.yaml.alias-cycle"))

    val mutual = Yaml.project(
      parse("root: &a\n  child: &b\n    back: *a\n"),
      YamlProjectionOptions(aliases = AliasPolicy.Resolve),
    )
    assert(mutual.value.isEmpty)
    assert(mutual.diagnostics.exists(_.code == "uniml.yaml.alias-cycle"))
  }

  test("alias expansion and expanded-node budgets are enforced") {
    val result = parse("base: &base [1, 2]\ncopy: *base\n")
    val expansions = Yaml.project(
      result,
      YamlProjectionOptions(aliases = AliasPolicy.Resolve, maxAliasExpansions = 0),
    )
    assert(expansions.value.isEmpty)
    assert(expansions.diagnostics.exists(_.code == "uniml.yaml.limit.expansion"))

    val nodes = Yaml.project(
      result,
      YamlProjectionOptions(aliases = AliasPolicy.Resolve, maxExpandedNodes = 1),
    )
    assert(nodes.value.isEmpty)
    assert(nodes.diagnostics.exists(_.code == "uniml.yaml.limit.expansion"))
  }

  test("duplicate mapping entries remain ordered and produce a warning") {
    val projection = Yaml.project(parse("a: 1\na: 2\n"))
    val mapping = documentValue(projection.value.get.asInstanceOf[YamlValue.Stream]).asInstanceOf[YamlValue.Mapping]
    assert(mapping.entries.size == 2)
    assert(mapping.entries.map(scalarStringKey) == Vector("a", "a"))
    assert(projection.diagnostics.exists(_.code == "uniml.yaml.duplicate-key"))
  }

  test("Failsafe, JSON, and Core schemas resolve independently") {
    val result = parse("[null, Null, true, TRUE, yes, 12, 1.5]\n")
    val failsafe = sequenceValues(Yaml.project(result, YamlProjectionOptions(schema = YamlSchema.Failsafe)))
    assert(failsafe.forall(_.isInstanceOf[YamlValue.Scalar]))
    assert(failsafe.head.asInstanceOf[YamlValue.Scalar].value.isInstanceOf[YamlScalar.StringValue])

    val json = sequenceValues(Yaml.project(result, YamlProjectionOptions(schema = YamlSchema.Json)))
    assert(json.head.asInstanceOf[YamlValue.Scalar].value == YamlScalar.NullValue("null"))
    assert(json(1).asInstanceOf[YamlValue.Scalar].value.isInstanceOf[YamlScalar.StringValue])
    assert(json(3).asInstanceOf[YamlValue.Scalar].value.isInstanceOf[YamlScalar.StringValue])

    val core = sequenceValues(Yaml.project(result))
    assert(core(1).asInstanceOf[YamlValue.Scalar].value == YamlScalar.NullValue("Null"))
    assert(core(3).asInstanceOf[YamlValue.Scalar].value == YamlScalar.BooleanValue(true, "TRUE"))
    assert(core(4).asInstanceOf[YamlValue.Scalar].value.isInstanceOf[YamlScalar.StringValue])
  }

  test("tabs, unpaired surrogates, and finite source limits are diagnosed") {
    val tabbed = parse("root:\n\tchild: 1\n")
    assert(tabbed.diagnostics.exists(_.code == "uniml.yaml.tab-indentation"))

    val surrogate = parse("value: \uD800\n")
    assert(surrogate.diagnostics.exists(_.code == "uniml.yaml.invalid-character"))

    val limited = Yaml.parse(SourceInput.fromString(source, "abcd"), YamlLimits(maxSourceCodePoints = 3))
    assert(limited.status == CompletionStatus.Halted)
    assert(limited.diagnostics.exists(_.code == "uniml.yaml.limit.source"))

    val lineLimited = Yaml.parse(SourceInput.fromString(source, "abcd\n"), YamlLimits(maxLineCodePoints = 3))
    assert(lineLimited.diagnostics.exists(_.code == "uniml.yaml.limit.line"))

    val scalarLimited = Yaml.parse(SourceInput.fromString(source, "abcd\n"), YamlLimits(maxScalarCodePoints = 3))
    assert(scalarLimited.diagnostics.exists(_.code == "uniml.yaml.limit.scalar"))

    val indentationLimited = Yaml.parse(SourceInput.fromString(source, "  a: 1\n"), YamlLimits(maxIndentation = 1))
    assert(indentationLimited.diagnostics.exists(_.code == "uniml.yaml.limit.indentation"))

    val anchorLimited = Yaml.parse(SourceInput.fromString(source, "a: &a 1\n"), YamlLimits(maxAnchors = 0))
    assert(anchorLimited.diagnostics.exists(_.code == "uniml.yaml.limit.anchors"))

    val aliasLimited = Yaml.parse(SourceInput.fromString(source, "a: &a 1\nb: *a\n"), YamlLimits(maxAliases = 0))
    assert(aliasLimited.diagnostics.exists(_.code == "uniml.yaml.limit.aliases"))

    val depthLimited = Yaml.parse(SourceInput.fromString(source, "a: 1\n"), YamlLimits(core = Limits(maxDepth = 1)))
    assert(depthLimited.diagnostics.exists(_.code == "uniml.limit.depth"))
  }

  test("malformed flow syntax returns a partial CST and a structured parse error") {
    val result = parse("value: [1, 2\n")
    assert(result.status == CompletionStatus.Incomplete)
    assert(sourceText(result) == "value: [1, 2\n")
    assert(result.diagnostics.exists(_.code == "uniml.yaml.unclosed-flow"))
    val projection = Yaml.project(result)
    assert(projection.value.isEmpty)
    assert(projection.diagnostics.exists(_.code == "uniml.yaml.unclosed-flow"))
  }

  private def parse(text: String): ParseResult = Yaml.parse(SourceInput.fromString(source, text))

  private def projected(result: ParseResult): YamlValue.Stream =
    val projection = Yaml.project(result)
    assert(!projection.diagnostics.exists(_.severity == Severity.Error), projection.diagnostics.mkString("\n"))
    projection.value.get.asInstanceOf[YamlValue.Stream]

  private def documentValue(stream: YamlValue.Stream): YamlValue = stream.documents.head.value.get

  private def sequenceValues(projection: YamlProjectionResult): Vector[YamlValue] =
    val stream = projection.value.get.asInstanceOf[YamlValue.Stream]
    documentValue(stream).asInstanceOf[YamlValue.Sequence].values

  private def scalarStringKey(entry: YamlEntry): String = entry.key match
    case YamlValue.Scalar(YamlScalar.StringValue(value, _, _), _, _) => value
    case other => fail(s"expected string key, found $other")

  private def sourceText(result: ParseResult): String =
    result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id).map(_.lexeme).mkString

  private def tokenShape(result: ParseResult): Vector[(String, String, SourceSpan)] =
    result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id).map(token => (token.kind, token.lexeme, token.span))

  private def branchKinds(result: ParseResult): Vector[String] =
    val resultKinds = Vector.newBuilder[String]
    var pending = result.roots.toList
    while pending.nonEmpty do
      pending.head match
        case UniNode.Branch(kind, edges, _, _) =>
          resultKinds += kind
          pending = edges.iterator.map(_.child).toList ::: pending.tail
        case UniNode.Token(_) => pending = pending.tail
    resultKinds.result()
