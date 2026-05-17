package scalascript.plugin

import org.scalatest.funsuite.AnyFunSuite

class SourceLanguageRegistryTest extends AnyFunSuite:

  test("ServiceLoader discovers the bundled scala-source plugin"):
    val canonical = SourceLanguageRegistry.all.map(_.canonicalName).toSet
    assert(canonical.contains("scala"), s"expected `scala`, got: $canonical")

  test("lookup resolves by canonical name"):
    assert(SourceLanguageRegistry.lookup("scala").exists(_.displayName.contains("Scala")))

  test("lookup of unknown language returns None"):
    assert(SourceLanguageRegistry.lookup("does-not-exist").isEmpty)

  test("knownLanguages includes scala"):
    assert(SourceLanguageRegistry.knownLanguages.contains("scala"))

  test("describe produces at least one line"):
    val lines = SourceLanguageRegistry.describe.linesIterator.toList
    assert(lines.nonEmpty)
    assert(lines.exists(_.contains("scala")))
