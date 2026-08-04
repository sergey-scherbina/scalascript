package scalascript.uniml.ssc

import scalascript.uniml.*
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import org.scalatest.funsuite.AnyFunSuite

/** An injected subtree must live in the FILE's coordinates, not its fence's.
  *
  * A child dialect sees only its own bytes, so it measures everything from
  * offset 0 of the fence. Splicing that in unchanged put the whole subtree — and
  * every diagnostic — in a coordinate space no reader shares: a fence starting
  * at file offset 42 produced a diagnostic reading `ssc:fence` line 1, and every
  * fence carried the SAME source id, so a diagnostic could not be traced to one.
  *
  * This matters more than it looks. Spans are how a compiler points at source,
  * so "UniML is the ScalaScript 3 front end" is only true if a span survives the
  * composition boundary. */
final class SscComposedSpansSpec extends AnyFunSuite:

  private val doc =
    "# Title\n\nSome prose here.\n\n```scalascript\ndef f(): Int = ??\n```\n"

  private def nodes(n: UniNode): Vector[UniNode] = n +: (n match
    case b: UniNode.Branch => b.edges.flatMap(e => nodes(e.child))
    case _                 => Vector.empty)

  private def spanOf(n: UniNode): SourceSpan = n match
    case UniNode.Token(t)  => t.span
    case b: UniNode.Branch => b.span

  test("a diagnostic from a fence points at the FILE, at the right offset and line") {
    val composed = SscCompose.parse(doc)
    val bodyStart = doc.indexOf("def f")
    assert(composed.diagnostics.nonEmpty, "the fixture must produce a diagnostic to locate")
    composed.diagnostics.foreach { d =>
      val span = d.span.getOrElse(fail(s"diagnostic without a span: ${d.message}"))
      assert(span.source.value == "ssc:file", s"${d.message} still reports ${span.source.value}")
      assert(span.start.offset >= bodyStart, s"${d.message} at ${span.start.offset}, before the fence body at $bodyStart")
      assert(span.start.line == 6, s"${d.message} reports line ${span.start.line}, the fence body is on line 6")
      assert(doc.charAt(span.start.offset) == '?', s"${d.message} points at '${doc.charAt(span.start.offset)}'")
    }
  }

  test("every node of an injected subtree is inside its parent and in file coordinates") {
    val composed = SscCompose.parse(doc)
    val all = nodes(composed.root)
    val spike = all.collect { case b: UniNode.Branch if b.kind.startsWith("spike.") => b }
    assert(spike.nonEmpty, "no ScalaScript subtree was injected — the fixture stopped exercising this")
    spike.foreach { b =>
      assert(b.span.source.value == "ssc:file", s"${b.kind} still reports ${b.span.source.value}")
      assert(b.span.start.offset >= doc.indexOf("def f"), s"${b.kind} starts before its fence body")
      assert(b.span.end.offset <= doc.length, s"${b.kind} ends past the file")
    }
  }

  /** The property, over real files rather than one fixture. */
  test("no injected span escapes the file, across the repository") {
    val root = Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("AGENTS.md")))
      .getOrElse(throw new IllegalStateException("repository root not found"))
    val files = Files.walk(root).iterator.asScala
      .filter(_.toString.endsWith(".ssc"))
      .filterNot(p =>
        p.toString.contains("/target/") || p.toString.contains("/.git/") ||
          p.toString.contains("/.worktrees/") || p.toString.contains("/bin/lib/"))
      .toVector.sortBy(_.toString)
    assert(files.sizeIs > 500, s"only ${files.size} .ssc found — the sweep silently shrank")
    val bad = files.flatMap { p =>
      val text = new String(Files.readAllBytes(p), "UTF-8")
      val composed = SscCompose.parse(text)
      val offenders = nodes(composed.root).map(spanOf).filter(s =>
        s.source.value != "ssc:file" || s.end.offset > text.length || s.start.offset > s.end.offset)
      if offenders.isEmpty then None
      else Some(s"${root.relativize(p)}: ${offenders.size} span(s) outside the file")
    }
    assert(bad.isEmpty, bad.take(10).mkString("\n"))
  }
