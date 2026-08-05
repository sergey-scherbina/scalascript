package scalascript.uniml.ssc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.UniNode
import scalascript.uniml.dialect.scalascript.{SpikeAst, SpikeTyped}
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

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

  /** Ceiling for the silent-drop census below, and it is ZERO by measurement, not by aspiration:
    * the census read 6,641 when it was written and reads 0 now. Every subtree the CST has either
    * reaches the AST or is named by an `Unsupported`. Raising this to admit a new drop would give
    * back the property the census exists to hold — say `Unsupported` instead. */
  private val DroppedCeiling = 0

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

  private def corpusFiles(root: Path): Vector[Path] =
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
    files

  test("the typed AST covers what the dialect parses, and says what it does not") {
    val root = repoRoot
    val files = corpusFiles(root)

    var nodes = 0L
    var parseErrors = 0L
    var astGaps = 0L
    val gapKinds = scala.collection.mutable.Map.empty[String, Int]

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
        case SpikeAst.PatUnsupported(k, _) =>
          if k == "spike.error" then parseErrors += 1
          else { astGaps += 1; gapKinds("pat:" + k) = gapKinds.getOrElse("pat:" + k, 0) + 1 }
        case _ => ()
      }
    }

    val typed = nodes - parseErrors - astGaps
    val ofParsed = 100.0 * typed / scala.math.max(1L, nodes - parseErrors)
    info(f"files=${files.size} nodes=$nodes typed=$typed parse-errors=$parseErrors ast-gaps=$astGaps")
    info(f"coverage of what the dialect PARSES: $ofParsed%.1f%%")
    gapKinds.toVector.sortBy(-_._2).take(10).foreach((k, c) => info(f"  gap $c%6d  $k"))

    // Floors, not equalities: adding modelled nodes must not force a number to
    // move, but a regression in what is modelled has to fail.
    assert(nodes > 100000, s"only $nodes nodes reached the projection — the composer path broke")
    // Raised 95.0 → 99.0 once the measurement reached 99.7%. A floor four points below the truth
    // stopped being a gate: it is what let a 96.5% reading pass while every `if` in the corpus
    // modelled both its branches as the words `then` and `else`.
    assert(ofParsed > 99.0, f"typed coverage of parsed nodes fell to $ofParsed%.1f%%")
  }

  /** The gap the number above CANNOT see, and the reason it reads high.
    *
    * `Unsupported` is honest about a shape the projection does not MODEL. It says nothing about
    * a subtree the projection never LOOKED AT: a dropped child produces no node, so it is absent
    * from both the numerator and the denominator. Dropping a construct therefore RAISES the
    * coverage figure — the metric rewards the one failure it exists to prevent, which is why
    * `given.body` could vanish for the whole corpus while the gate read 98.3% and passed.
    *
    * This census walks the other way: from the CST, asking of every branch child whether it
    * reached the AST at all. A child counts as consumed when its span appears among the AST's
    * spans, since every projected node carries the span of the CST node it came from. Descent
    * stops at a node that is missing — the drop is recorded once, at its boundary — and at one
    * that projected as `Unsupported`, whose subtree is already counted above and is not a second
    * gap.
    *
    * BRANCH children only, and the limitation is deliberate rather than an oversight: a token is
    * frequently consumed as a STRING (`def.name`, `sel.field`, `cc.field`), which leaves a name
    * in the AST but no node and therefore no span. Counting tokens here would report every
    * identifier in the corpus as dropped. Token-level drops are real too — `(x)` projecting as
    * `UnitLit` is one — and are asserted by SHAPE in `SpikeTypedRolesSpec` instead of by count. */
  test("every CST subtree the projection silently drops is named and counted") {
    val root = repoRoot
    val files = corpusFiles(root)

    var branches = 0L
    var dropped = 0L
    val dropKinds = scala.collection.mutable.Map.empty[String, Int]
    val dropWhere = scala.collection.mutable.Map.empty[String, String]

    files.foreach { p =>
      scalaSubtrees(SscCompose.parse(new String(Files.readAllBytes(p), "UTF-8")).root).foreach { sr =>
        val ast = SpikeTyped.module(sr)
        val all = SpikeAst.walk(ast)
        val seen = all.map(_.span).toSet
        val gaps = all.collect {
          case SpikeAst.Unsupported(_, s)     => s
          case SpikeAst.UnsupportedDecl(_, s) => s
        }.toSet

        // Lost means NOTHING of the subtree survived — not merely that this node's own span is
        // absent. A projection is allowed to unwrap transparently: `spike.paren` yields its inner
        // expression, so the paren's span is gone while the content is fully present. Judging on
        // the node's own span alone called all 370 of those a drop, which would have sent the
        // first fix at a construct that works. The token case is the real one and this still
        // catches it: `(x)` projects as `UnitLit`, so the identifier reaches nothing.
        def reached(n: UniNode): Boolean = n match
          case b: UniNode.Branch => seen.contains(b.span) || b.edges.exists(e => reached(e.child))
          case UniNode.Token(t)  => seen.contains(t.span)

        def descend(n: UniNode): Unit = n match
          case b: UniNode.Branch =>
            branches += 1
            b.edges.foreach { e =>
              e.child match
                case c: UniNode.Branch =>
                  val role = e.role.getOrElse("«no role»")
                  if gaps.contains(c.span) then ()          // an honest gap, counted by the test above
                  else if reached(c) then descend(c)
                  else
                    dropped += 1
                    val key = s"${b.kind} / $role -> ${c.kind}"
                    dropKinds(key) = dropKinds.getOrElse(key, 0) + 1
                    dropWhere.getOrElseUpdate(key, s"${root.relativize(p)}:${c.span.start.line}")
                case _ => ()
            }
          case _ => ()

        descend(sr)
      }
    }

    info(f"branches=$branches silently-dropped-subtrees=$dropped")
    dropKinds.toVector.sortBy(-_._2).take(20).foreach((k, c) => info(f"  drop $c%6d  $k   e.g. ${dropWhere(k)}"))

    // A ceiling, not a floor: this number may only go DOWN. It is the count of subtrees the
    // projection neither modelled nor admitted to — the ones no `Unsupported` will ever report.
    assert(dropped <= DroppedCeiling, s"silent drops rose to $dropped (ceiling $DroppedCeiling)")
  }
