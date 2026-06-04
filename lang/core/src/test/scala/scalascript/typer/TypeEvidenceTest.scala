package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class TypeEvidenceTest extends AnyFunSuite:

  private def moduleOf(scalascriptSource: String): scalascript.ast.Module =
    val withFence =
      s"""# Test
         |
         |```scalascript
         |$scalascriptSource
         |```
         |""".stripMargin
    Parser.parse(withFence)

  private def summaries(scalascriptSource: String): List[DefSummary] =
    val typed = Typer.typeCheck(moduleOf(scalascriptSource))
    val buf   = scala.collection.mutable.ListBuffer.empty[DefSummary]
    def walk(s: TypedSection): Unit =
      s.definitions.foreach {
        case TypedDef.CodeBlock(_, _, defs) => buf ++= defs
        case _                              => ()
      }
      s.subsections.foreach(walk)
    typed.sections.foreach(walk)
    buf.toList

  private def summaryOf(scalascriptSource: String, name: String): DefSummary =
    val all = summaries(scalascriptSource)
    all.find(_.name == name).getOrElse {
      fail(s"no DefSummary found for `$name`; got: ${all.map(_.name)}")
    }

  test("SType.containsAny detects direct and structural Any"):
    assert(SType.Any.containsAny)
    assert(SType.Named("List", List(SType.Any)).containsAny)
    assert(SType.Function(List(SType.Int), SType.Any).containsAny)
    assert(!SType.Named("List", List(SType.Int)).containsAny)

  test("TypeEvidence constructors preserve kind and Any classification"):
    val declared = TypeEvidence.declared(SType.Any, reason = Some("explicit annotation"))
    val inferred = TypeEvidence.inferred(SType.Int, reason = Some("literal"))
    val unknown = TypeEvidence.unknown(
      SType.Function(Nil, SType.Any),
      reason = Some("unsupported body")
    )

    assert(declared.kind == TypeEvidenceKind.Declared)
    assert(declared.containsAny)
    assert(inferred.kind == TypeEvidenceKind.Inferred)
    assert(!inferred.containsAny)
    assert(unknown.kind == TypeEvidenceKind.Unknown)
    assert(unknown.containsAny)

  test("AnyEvidenceInventory counts Any by evidence kind"):
    val summaries = List(
      DefSummary(
        "declaredAny",
        SymbolKind.Val,
        SType.Any,
        Nil,
        Some(TypeEvidence.declared(SType.Any))
      ),
      DefSummary(
        "dynamicAny",
        SymbolKind.Val,
        SType.Any,
        Nil,
        Some(TypeEvidence.dynamic())
      ),
      DefSummary(
        "unknownReturn",
        SymbolKind.Def,
        SType.Function(Nil, SType.Any),
        Nil,
        Some(TypeEvidence.unknown(SType.Function(Nil, SType.Any)))
      ),
      DefSummary(
        "precise",
        SymbolKind.Val,
        SType.Int,
        Nil,
        Some(TypeEvidence.inferred(SType.Int))
      ),
      DefSummary("legacyMissingEvidence", SymbolKind.Val, SType.Any, Nil)
    )

    val counts = AnyEvidenceInventory.count(summaries)
    assert(counts.declared == 1)
    assert(counts.dynamic == 1)
    assert(counts.unknown == 2)
    assert(counts.inferred == 0)
    assert(counts.total == 4)

  test("typer marks explicit Any annotations as declared evidence"):
    val d = summaryOf("val x: Any = 1", "x")
    assert(d.tpe == SType.Any)
    assert(d.evidence.exists(_.kind == TypeEvidenceKind.Declared))
    assert(d.evidence.exists(_.containsAny))

  test("typer marks supported unannotated summaries as inferred evidence"):
    val d = summaryOf("val n = 42", "n")
    assert(d.tpe == SType.Int)
    assert(d.evidence.exists(_.kind == TypeEvidenceKind.Inferred))
    assert(!d.evidence.exists(_.containsAny))

  test("typer marks unsupported inferred Any as unknown evidence"):
    val d = summaryOf("def baz() = someUndefinedComplexThing()", "baz")
    assert(d.tpe == SType.Function(Nil, SType.Any))
    assert(d.evidence.exists(_.kind == TypeEvidenceKind.Unknown))
    assert(d.evidence.exists(_.containsAny))
