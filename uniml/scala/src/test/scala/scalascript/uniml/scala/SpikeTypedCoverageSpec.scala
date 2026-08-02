package scalascript.uniml.scala

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.UniNode
import scalascript.uniml.spike.{SpikeAst, SpikeTyped}
import java.nio.file.{Files, Path, Paths}
import _root_.scala.jdk.CollectionConverters.*

/** Coverage of the typed AST over every `.ssc` in the repository.
  *
  * The typed projection's honesty rests on `Unsupported` never being silently
  * dropped, which only means something if somebody counts them. This is the
  * count, with floors so it cannot quietly regress.
  *
  * MEASURED THROUGH THE COMPOSER, and that is not a detail. A `.ssc` is a
  * literate document — YAML front matter, Markdown prose, fenced code — so
  * feeding a whole one to the bare ScalaScript dialect measures markdown as if it
  * were broken ScalaScript. An earlier revision of this gate did exactly that and
  * reported 33,487 "parse errors"; 18,782 of them were BACKTICKS, and the same
  * mistake made a breadth census read 3.8% clean when the real figure through the
  * composer is 90.0%. Through `SscCompose` each dialect sees only its own bytes.
  *
  * TWO numbers, because they are two different problems:
  *
  *   - `spike.error` nodes are places the DIALECT did not parse: breadth (SSC3-B),
  *     not typing. No AST can type what the CST does not represent.
  *   - every other `Unsupported` is a shape the CST HAS and this projection does
  *     not model yet. That is the real SSC3-P worklist. */
final class SpikeTypedCoverageSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  /** the ScalaScript subtrees the composer spliced under the code fences */
  private def scalaSubtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b)
      else b.edges.flatMap(e => scalaSubtrees(e.child))
    case _ => Vector.empty

  test("the typed AST covers what the dialect parses, and says what it does not") {
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

    var nodes = 0L
    var parseErrors = 0L
    var astGaps = 0L
    val gapKinds = _root_.scala.collection.mutable.Map.empty[String, Int]

    files.foreach { p =>
      val text = new String(Files.readAllBytes(p), "UTF-8")
      val all = scalaSubtrees(SscCompose.parse(text).root)
        .flatMap(sr => SpikeAst.walk(SpikeTyped.module(sr)))
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

    val typed = nodes - parseErrors - astGaps
    val ofParsed = 100.0 * typed / _root_.scala.math.max(1L, nodes - parseErrors)
    info(f"files=${files.size} nodes=$nodes typed=$typed parse-errors=$parseErrors ast-gaps=$astGaps")
    info(f"coverage of what the dialect PARSES: $ofParsed%.1f%%")
    gapKinds.toVector.sortBy(-_._2).take(10).foreach((k, c) => info(f"  gap $c%6d  $k"))

    // Floors, not equalities: adding modelled nodes must not force a number to
    // move, but a regression in what is modelled has to fail.
    assert(nodes > 100000, s"only $nodes nodes reached the projection — the composer path broke")
    assert(ofParsed > 95.0, f"typed coverage of parsed nodes fell to $ofParsed%.1f%%")
  }
