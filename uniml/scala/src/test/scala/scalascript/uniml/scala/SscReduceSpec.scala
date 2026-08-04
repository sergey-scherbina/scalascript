package scalascript.uniml.scala

import scalascript.uniml.*
import scalascript.uniml.spike.SpikeDialect
import org.scalatest.funsuite.AnyFunSuite

/** Delta-debugging for the ScalaScript front: shrink an input to the smallest
  * one that still reproduces the SAME diagnostic.
  *
  * This exists because two obvious methods lied. Reading the FIRST diagnostic's
  * position finds a recovery landing site, not the origin — in
  * `examples/std-ui/textarea.ssc` it points at line 46 while the construct
  * responsible is at line 9. And bisecting a fence body BY LINES splits a
  * multi-line string in half, so every prefix ending inside one fails for a
  * reason the file does not have; it pointed confidently at the wrong line.
  *
  * THE GUARD IS THE WHOLE DESIGN. "still fails" is not enough and neither is
  * "fails no more often": a torn string does both. A candidate is accepted only
  * if the target message survives AND it introduces NO message the original did
  * not have. With that, textarea reduces from 44 lines to 5 and keeps its
  * diagnostic; with the weaker guard the same reducer produced a 2-line
  * fragment whose failure was its own. */
object SscReduce:

  def diagnostics(source: String): Vector[String] =
    UniML.parse(SourceInput.fromString(SourceId("reduce"), source), SpikeDialect)
      .diagnostics.map(_.message)

  /** Smallest line subset still reproducing `target`, introducing nothing new. */
  def reduce(source: String, target: String): Vector[String] =
    val original = diagnostics(source).toSet
    def reproduces(lines: Vector[String]): Boolean =
      val d = diagnostics(lines.mkString("\n"))
      d.contains(target) && d.toSet.subsetOf(original)
    var lines = source.linesIterator.toVector
    var granularity = 2
    var working = true
    while working do
      working = false
      val chunk = math.max(1, lines.size / granularity)
      var i = 0
      var shrank = false
      while i < lines.size && !shrank do
        val candidate = lines.take(i) ++ lines.drop(i + chunk)
        if candidate.nonEmpty && reproduces(candidate) then
          lines = candidate; shrank = true; working = true
          granularity = math.max(2, granularity - 1)
        else i += chunk
      if !shrank && granularity < lines.size then
        granularity = math.min(lines.size, granularity * 2); working = true
    lines

final class SscReduceSpec extends AnyFunSuite:

  test("a reduction keeps the diagnostic and introduces nothing new") {
    // a def whose body is fine, plus one line that is not
    val source = "def a(): Int = 1\ndef b(): Int = ?\ndef c(): Int = 3\n"
    val target = SscReduce.diagnostics(source).headOption
      .getOrElse(fail("the fixture stopped producing a diagnostic"))
    val reduced = SscReduce.reduce(source, target)
    assert(reduced.sizeIs < source.linesIterator.size, s"no reduction: ${reduced.size} lines")
    val after = SscReduce.diagnostics(reduced.mkString("\n"))
    assert(after.contains(target), "the reduction lost the diagnostic it was reducing")
    assert(
      after.toSet.subsetOf(SscReduce.diagnostics(source).toSet),
      s"the reduction INTRODUCED a diagnostic: ${after.toSet -- SscReduce.diagnostics(source).toSet}",
    )
  }

  test("a reduction targeting one error does not drag in another") {
    // two INDEPENDENT bad lines: reducing towards the first must not keep the
    // second, and the subset guard is what makes that hold — "still fails" alone
    // would happily keep either.
    val source = "def a(): Int = 1\ndef b(): Int = ?\ndef c(): Int = 3\ncase 1 =>\n"
    val all = SscReduce.diagnostics(source)
    assert(all.sizeIs >= 2, s"the fixture must produce two distinct errors, got $all")
    val target = all.head
    val reduced = SscReduce.reduce(source, target).mkString("\n")
    val after = SscReduce.diagnostics(reduced).toSet
    assert(after.contains(target), "the reduction lost its target")
    assert(after.subsetOf(all.toSet), s"the reduction introduced ${after -- all.toSet}")
    assert(reduced.linesIterator.size < 4, s"no reduction happened:\n$reduced")
  }
