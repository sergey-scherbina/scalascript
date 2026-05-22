package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

class SectionDiffTest extends AnyFunSuite:

  private def parse(src: String): scalascript.ast.Module = Parser.parse(src)

  private def snaps(src: String): List[SectionSnapshot] =
    val (_, s) = Typer.typeCheckIncrementalModule(parse(src), Nil)
    s

  private val src3 =
    """# Alpha
      |
      |```scalascript
      |val a = 1
      |```
      |
      |# Beta
      |
      |```scalascript
      |val b = 2
      |```
      |
      |# Gamma
      |
      |```scalascript
      |val c = 3
      |```
      |""".stripMargin

  test("no diff when nothing changes"):
    val s = snaps(src3)
    val diff = SectionDiff.compute(s, s)
    assert(diff.isEmpty)

  test("all sections added when prev is empty"):
    val s = snaps(src3)
    val diff = SectionDiff.compute(Nil, s)
    assert(diff.added.toSet == Set("Alpha", "Beta", "Gamma"))
    assert(diff.modified.isEmpty)
    assert(diff.removed.isEmpty)

  test("all sections removed when next is empty"):
    val s = snaps(src3)
    val diff = SectionDiff.compute(s, Nil)
    assert(diff.removed.toSet == Set("Alpha", "Beta", "Gamma"))
    assert(diff.added.isEmpty)
    assert(diff.modified.isEmpty)

  test("modified when one section body changes"):
    val prev = snaps(src3)
    val modified = src3.replace("val b = 2", "val b = 99")
    val next = snaps(modified)
    val diff = SectionDiff.compute(prev, next)
    assert(diff.modified == List("Beta"))
    assert(diff.added.isEmpty)
    assert(diff.removed.isEmpty)

  test("added when a new section appears"):
    val prev = snaps(src3)
    val withExtra = src3 + "\n# Delta\n\n```scalascript\nval d = 4\n```\n"
    val next = snaps(withExtra)
    val diff = SectionDiff.compute(prev, next)
    assert(diff.added == List("Delta"))
    assert(diff.modified.isEmpty)
    assert(diff.removed.isEmpty)

  test("removed when a section is deleted"):
    val prev = snaps(src3)
    // Use a 2-section source instead
    val src2 = """# Alpha
                 |
                 |```scalascript
                 |val a = 1
                 |```
                 |
                 |# Gamma
                 |
                 |```scalascript
                 |val c = 3
                 |```
                 |""".stripMargin
    val next = snaps(src2)
    val diff = SectionDiff.compute(prev, next)
    assert(diff.removed == List("Beta"))
    assert(diff.added.isEmpty)

  test("diff.show formats correctly"):
    val diff = SectionDiff.Diff(
      added    = List("New"),
      modified = List("Changed"),
      removed  = List("Gone")
    )
    val s = diff.show
    assert(s.contains("§ Changed"))
    assert(s.contains("+§ New"))
    assert(s.contains("-§ Gone"))

  test("diff.show is empty string when nothing changed"):
    val diff = SectionDiff.Diff(Nil, Nil, Nil)
    assert(diff.show.isEmpty)
    assert(diff.isEmpty)

  test("result lists are sorted alphabetically"):
    val src = """# Zeta
                |
                |```scalascript
                |val z = 1
                |```
                |
                |# Alpha
                |
                |```scalascript
                |val a = 2
                |```
                |""".stripMargin
    val prev = snaps(src)
    val modified = src.replace("val z = 1", "val z = 9").replace("val a = 2", "val a = 9")
    val next = snaps(modified)
    val diff = SectionDiff.compute(prev, next)
    assert(diff.modified == diff.modified.sorted)
