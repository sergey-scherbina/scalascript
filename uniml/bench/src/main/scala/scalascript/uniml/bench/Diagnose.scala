package scalascript.uniml.bench

import scalascript.uniml.*
import scalascript.uniml.ssc.SscCompose

/** What the composed front actually complains about in one `.ssc`, with source lines beside the
  * diagnostics.
  *
  * Exists because picking the next breadth slice off a histogram picks a SHAPE, and a shape does
  * not tell you which construct produced it — `expected type, found '='` is a symptom shared by
  * several gaps. This prints the offending line, so the construct names itself.
  *
  * Run: `sbt "unimlBench/runMain scalascript.uniml.bench.Diagnose <path.ssc>"`
  */
object Diagnose:
  def main(args: Array[String]): Unit =
    if args.isEmpty then { println("usage: Diagnose <path.ssc> [more.ssc …]"); return }
    args.foreach { path =>
      val p = java.nio.file.Paths.get(path)
      val src = new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8)
      val lines = src.split("\n", -1)
      val composed = SscCompose.parse(src)
      println(s"=== $path — ${composed.diagnostics.size} diagnostics, status=${composed.status}")
      // Group by line so a construct that produces a cascade is read as ONE gap, not as six.
      composed.diagnostics.groupBy(_.span.map(_.start.line).getOrElse(0)).toVector.sortBy(_._1).foreach {
        case (line, ds) =>
          val text = if line >= 1 && line <= lines.length then lines(line - 1) else "<no line>"
          println(f"  line $line%4d  ${ds.size}%2d diag  | ${text.trim}")
          ds.take(3).foreach(d => println(s"             ${d.message}"))
      }
    }
