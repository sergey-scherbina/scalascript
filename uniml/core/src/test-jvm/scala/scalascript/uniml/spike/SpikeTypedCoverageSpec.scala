package scalascript.uniml.spike

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Coverage of the typed AST over every `.ssc` in the repository.
  *
  * The typed projection's honesty rests on `Unsupported` never being silently
  * dropped, which only means something if somebody counts them. This is the
  * count, with a floor so it cannot quietly regress.
  *
  * TWO numbers, because they are two different problems:
  *
  *   - `spike.error` nodes are places the DIALECT did not parse. That is breadth
  *     (SSC3-B), not typing — no AST can type what the CST does not represent.
  *   - every other `Unsupported` is a shape the CST HAS and this projection does
  *     not model yet. That is the real SSC3-P worklist.
  *
  * Conflating them would let a breadth failure read as an AST failure and hide
  * the fact that the projection is nearly complete over what actually parses. */
final class SpikeTypedCoverageSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  test("the typed AST covers what the dialect parses, and says what it does not") {
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p =>
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")

    var nodes = 0L
    var parseErrors = 0L
    var astGaps = 0L
    val gapKinds = scala.collection.mutable.Map.empty[String, Int]

    files.foreach { p =>
      val text = new String(Files.readAllBytes(p), "UTF-8")
      val parsed = UniML.parse(SourceInput.fromString(SourceId(p.toString), text), SpikeDialect)
      parsed.roots.headOption.foreach { r =>
        val all = SpikeAst.walk(SpikeTyped.module(r))
        nodes += all.size
        all.foreach {
          case SpikeAst.Unsupported(k, _) =>
            if k == "spike.error" then parseErrors += 1
            else { astGaps += 1; gapKinds(k) = gapKinds.getOrElse(k, 0) + 1 }
          case SpikeAst.UnsupportedDecl(k, _) =>
            if k == "spike.error" then parseErrors += 1
            else { astGaps += 1; gapKinds("decl:" + k) = gapKinds.getOrElse("decl:" + k, 0) + 1 }
          case _ => ()
        }
      }
    }

    val typed = nodes - parseErrors - astGaps
    val ofParsed = 100.0 * typed / (nodes - parseErrors)
    info(f"files=${files.size} nodes=$nodes typed=$typed parse-errors=$parseErrors ast-gaps=$astGaps")
    info(f"coverage of what the dialect PARSES: $ofParsed%.1f%%")
    gapKinds.toVector.sortBy(-_._2).take(10).foreach((k, c) => info(f"  gap $c%6d  $k"))

    // Floors, not equalities: a fix that adds nodes must not have to move a number,
    // but a REGRESSION in what the projection models has to fail.
    assert(ofParsed > 95.0, f"typed coverage of parsed nodes fell to $ofParsed%.1f%%")
    assert(astGaps < 12000, s"AST gaps grew to $astGaps")
  }
