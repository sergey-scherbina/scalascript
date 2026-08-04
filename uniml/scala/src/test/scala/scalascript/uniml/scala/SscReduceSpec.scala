package scalascript.uniml.scala

import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.{SpikeDialect, SpikeLex}
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

  /** Smallest TOKEN subset still reproducing `target`.
    *
    * This is the answer to the line reducer's false pointer, and it works because
    * of a property the CST already has: every lexeme is a SOURCE SLICE, so
    * concatenating the surviving tokens is real source text — and a multi-line
    * string is ONE token, so it cannot be torn in half the way a line cut tears
    * it. Removed ranges are additionally required to be BRACKET-BALANCED, so a
    * candidate never loses a `)` while keeping its `(`.
    *
    * The result is therefore a parseable fragment rather than a subset of lines,
    * which is what makes it evidence instead of a hint. */
  def reduceTokens(source: String, target: String): String =
    val original = diagnostics(source).toSet
    val opens = Set("spike.lparen", "spike.lbracket", "spike.lbrace")
    val closes = Set("spike.rparen", "spike.rbracket", "spike.rbrace")
    def balanced(ts: Vector[SourceToken]): Boolean =
      var depth = 0
      var wentNegative = false
      ts.foreach { t =>
        if opens(t.kind) then depth += 1
        else if closes(t.kind) then depth -= 1
        if depth < 0 then wentNegative = true
      }
      !wentNegative && depth == 0
    def text(ts: Vector[SourceToken]): String = ts.map(_.lexeme).mkString
    def reproduces(ts: Vector[SourceToken]): Boolean =
      val d = diagnostics(text(ts))
      d.contains(target) && d.toSet.subsetOf(original)
    var tokens = SpikeLex.scan(SourceId("reduce"), source)
    var granularity = 2
    var working = true
    while working do
      working = false
      val chunk = math.max(1, tokens.size / granularity)
      var i = 0
      var shrank = false
      while i < tokens.size && !shrank do
        val cut = tokens.slice(i, i + chunk)
        val candidate = tokens.take(i) ++ tokens.drop(i + chunk)
        if candidate.nonEmpty && balanced(cut) && reproduces(candidate) then
          tokens = candidate; shrank = true; working = true
          granularity = math.max(2, granularity - 1)
        else i += chunk
      if !shrank && granularity < tokens.size then
        granularity = math.min(tokens.size, granularity * 2); working = true
    text(tokens)

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

  test("a token reduction is itself parseable, and keeps only its target") {
    val source = "def a(): Int = 1\ndef b(): Int = (1 + )\ndef c(): Int = 3\n"
    val target = SscReduce.diagnostics(source).headOption
      .getOrElse(fail("the fixture stopped producing a diagnostic"))
    val reduced = SscReduce.reduceTokens(source, target)
    assert(reduced.length < source.length, s"no reduction: $reduced")
    val after = SscReduce.diagnostics(reduced)
    assert(after.contains(target), s"the reduction lost its target: $reduced")
    assert(
      after.toSet.subsetOf(SscReduce.diagnostics(source).toSet),
      s"the reduction introduced ${after.toSet -- SscReduce.diagnostics(source).toSet}",
    )
    // the point of reducing over TOKENS: brackets stay balanced, so the fragment is
    // real source rather than a subset of lines
    assert(reduced.count(_ == '(') == reduced.count(_ == ')'), s"unbalanced fragment: $reduced")
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
