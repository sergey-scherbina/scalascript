package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite

class SourceLanguageRegistryTest extends AnyFunSuite:

  test("ServiceLoader discovers the bundled scala-source plugin"):
    val canonical = SourceLanguageRegistry.all.map(_.canonicalName).toSet
    assert(canonical.contains("scala"), s"expected `scala`, got: $canonical")

  test("ServiceLoader discovers all built-in fenced source languages"):
    val canonical = SourceLanguageRegistry.all.map(_.canonicalName).toSet
    val expected = Set("scala", "html", "css", "javascript", "xml", "sql", "transaction")
    assert(expected.subsetOf(canonical), s"missing ${expected.diff(canonical)} from $canonical")

  test("lookup resolves by canonical name"):
    assert(SourceLanguageRegistry.lookup("scala").exists(_.displayName.contains("Scala")))

  test("lookup resolves JavaScript alias"):
    assert(SourceLanguageRegistry.lookup("js").exists(_.canonicalName == "javascript"))

  test("lookup of unknown language returns None"):
    assert(SourceLanguageRegistry.lookup("does-not-exist").isEmpty)

  test("knownLanguages includes scala"):
    assert(SourceLanguageRegistry.knownLanguages.contains("scala"))

  test("describe produces at least one line"):
    val lines = SourceLanguageRegistry.describe.linesIterator.toList
    assert(lines.nonEmpty)
    assert(lines.exists(_.contains("scala")))
