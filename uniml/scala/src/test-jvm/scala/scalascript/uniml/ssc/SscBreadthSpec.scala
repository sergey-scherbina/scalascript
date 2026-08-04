package scalascript.uniml.ssc

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite

/** How much real ScalaScript the composed front parses — SSC3-B's measure.
  *
  * DIAGNOSTICS, not AST node counts. `SpikeTypedCoverageSpec` counts
  * `spike.error` NODES and is blind to this: fixing `case object`, which removed
  * 470 diagnostics and made 12 more files parse completely clean, moved its
  * numbers by exactly ZERO, because error recovery produced a similar node
  * shape either way. Two gates, two questions — this one asks whether the parse
  * SUCCEEDED, that one asks whether the AST models what the parse produced.
  *
  * Through `SscCompose`, so each dialect sees only its own bytes: a `.ssc` is a
  * literate document and measuring the bare dialect against a whole file counts
  * markdown as broken ScalaScript. */
final class SscBreadthSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  test("the composed front parses the repository's ScalaScript") {
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p =>
        // `bin/` is INSTALLED OUTPUT — `install.sh --dev` copies the whole runtime there,
        // so leaving it in made the corpus grow from 1,145 files to 1,406 the moment
        // somebody ran the installer, and every count moved with it.
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")

    var clean = 0
    var total = 0L
    val shapes = scala.collection.mutable.Map.empty[String, Int]
    val worst = scala.collection.mutable.ArrayBuffer.empty[(String, Int)]

    files.foreach { p =>
      val composed = SscCompose.parse(new String(Files.readAllBytes(p), "UTF-8"))
      if composed.diagnostics.isEmpty then clean += 1
      else worst += ((root.relativize(p).toString, composed.diagnostics.size))
      total += composed.diagnostics.size
      composed.diagnostics.foreach { d =>
        val token = "'([^']*)'".r.findFirstMatchIn(d.message).map(_.group(1)).getOrElse("?")
        val shape = d.message.replaceAll("'[^']*'", "'X'") + " <- " + token
        shapes(shape) = shapes.getOrElse(shape, 0) + 1
      }
    }

    val pct = 100.0 * clean / files.size
    info(f"files=${files.size} clean=$clean ($pct%.1f%%) diagnostics=$total")
    shapes.toVector.sortBy(-_._2).take(8).foreach((s, c) => info(f"  $c%5d  $s"))
    worst.sortBy(-_._2).take(5).foreach((f, c) => info(f"  worst $c%5d  $f"))

    // Floors: a fix must be free to improve these without editing the test, but a
    // regression in what the front accepts has to fail.
    //
    // ZERO IS NOT THE TARGET, and measuring said so. An UNTAGGED fence (```) defaults
    // to ScalaScript, so a file whose fence holds a protocol diagram, shell output or
    // pseudocode reports diagnostics no parser fix can remove — `mapreduce/shuffle.ssc`
    // spends 18 on `Phase A (map stage):`. Measured 2026-08-04: of 64 files with any
    // diagnostic, 17 hold an untagged fence and those files account for 129 of 366
    // diagnostics. That 129 is an UPPER bound on the unreachable share — such a file
    // may also have real gaps in its tagged fences — but the reachable floor is clearly
    // above zero, and chasing zero would push toward changing the untagged default or
    // teaching the parser prose, both wrong.
    assert(pct > 90.0, f"clean-parse rate fell to $pct%.1f%%")
    assert(total < 900, s"diagnostics grew to $total")
  }
