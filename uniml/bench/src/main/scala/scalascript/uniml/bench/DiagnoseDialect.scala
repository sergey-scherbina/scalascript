package scalascript.uniml.bench

import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.SpikeDialect

/** Dialect-arm twin of [[Diagnose]]: the BARE ScalaScript parse of std/actors.ssc, with the
  * diagnostic list the Describe pin summarises. Born in stage-10 phase 2b, where the summary
  * (43 vs 41) said the immutable-cursor rewrite diverged and THIS dump named the two lines —
  * front-matter `key: value` colons riding the old cursor's trivia-migration accident. Keep it:
  * a pin without the instrument that explains a miss invites waving the miss through. */
object DiagnoseDialect:
  def main(args: Array[String]): Unit =
    val src = SpikeParserBench.readActors
    val r = UniML.parse(SourceInput.fromString(SourceId("bench:actors"), src), SpikeDialect)
    println(s"status=${r.status} diags=${r.diagnostics.size} nodes=${r.roots.map(count).sum}")
    val lines = src.split("\n", -1)
    r.diagnostics.foreach { d =>
      val ln = d.span.map(_.start.line).getOrElse(0)
      val text = if ln >= 1 && ln <= lines.length then lines(ln - 1).trim else "<no line>"
      println(f"line $ln%4d ${d.code} ${d.message} | $text")
    }
  private def count(n: UniNode): Int = n match
    case b: UniNode.Branch => 1 + b.edges.map(e => count(e.child)).sum
    case _                 => 1
