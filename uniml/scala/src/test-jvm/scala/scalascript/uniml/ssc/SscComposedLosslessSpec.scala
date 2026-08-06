package scalascript.uniml.ssc

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

/** The COMPOSED tree reconstructs its source exactly — the property the whole front-end decision
  * rests on, checked on the tree v3 would actually consume.
  *
  * `SpikeLosslessSpec` checks the bare dialect and cannot see this: it never injects. The composer
  * used to APPEND an injected subtree instead of splicing it where the fence body was, so an
  * in-order walk produced the right characters in the wrong order — the closing fence marker came
  * out before the code. Every character was present, so a length or multiset check would have
  * passed; only comparing the STRING catches it.
  *
  * Found by publishing the artifact and consuming it from outside the build, which is the only
  * vantage point from which the composed tree is the thing under test rather than an intermediate.
  */
final class SscComposedLosslessSpec extends AnyFunSuite:

  private def repoRoot: Path =
    Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))

  private def text(n: UniNode): String = n match
    case b: UniNode.Branch => b.edges.map(e => text(e.child)).mkString
    case UniNode.Token(t)  => t.lexeme

  test("every .ssc reconstructs exactly from the composed tree") {
    val root = repoRoot
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p => p.toString.contains("/target/") || p.toString.contains("/.git/") ||
        p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")

    val broken = files.flatMap { p =>
      val src = new String(Files.readAllBytes(p), "UTF-8")
      val round = text(SscCompose.parse(src).root)
      if round == src then None
      else
        // Report WHERE, not just that: the first differing offset is what names the construct.
        val i = src.zip(round).indexWhere((a, b) => a != b)
        Some(root.relativize(p).toString -> (if i < 0 then s"length ${src.length} vs ${round.length}" else s"first differs at $i"))
    }
    info(s"files=${files.size} exact=${files.size - broken.size}")
    broken.foreach((f, why) => info(s"  $f — $why"))

    // Frozen as a SET, not a count. A count lets one file break while another is fixed and stays
    // green; the set catches both directions, and a file leaving it is as much news as a file
    // joining it.
    //
    // Two composer defects are fixed and took this from 0 exact to 1,176: an injected subtree was
    // APPENDED rather than spliced (the closing fence marker came out before the code), and an
    // INDENTED code block was being injected at all — it is a `markdown.code-block` with no info
    // string, so it fell through to the untyped-fence default, and its body is interleaved with a
    // per-line indent token, so replacing the body with one subtree cannot preserve order.
    //
    // The remaining ones are a THIRD site, in markdown rather than the composer: a paragraph's
    // continuation line loses its leading whitespace, `+\n   second` coming back as `+\nsecond`
    // with the spaces moved to the end. Same shape — order, not loss. Filed in uniml/BACKLOG.md.
    //
    // One of the three, `dep-cps-ping.ssc`, was the CODE-SPAN case of that site and is fixed
    // (`md-continuation-prefix-inside-code-span`): a span crossing the break swallowed the newline
    // into its own lexeme, so the prefix had no break piece to follow.
    //
    // THE TWO BELOW ARE NOT THAT SITE, and the sentence above used to claim all three were. Their
    // first divergence is inside a FENCED BLOCK's body, not a paragraph continuation:
    // `nodes.ssc:67` at the newline ending the last body line before the closing fence, and
    // `streams.ssc:211` at a body line beginning `//   ↑`. Attributing them to the paragraph rule
    // is what made them look like one fix; they want their own tracing.
    val known = Set(
      "v1/runtime/std/nodes.ssc",
      "v1/runtime/std/streams.ssc",
    )
    val got = broken.map(_._1).toSet
    assert(got == known,
      s"composed round-trip changed.\n  newly broken: ${(got -- known).toVector.sorted}\n  newly exact:  ${(known -- got).toVector.sorted}")
  }
