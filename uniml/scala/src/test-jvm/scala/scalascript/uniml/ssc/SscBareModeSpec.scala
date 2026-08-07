package scalascript.uniml.ssc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.UniNode

/** BARE mode — a `.ssc` with no code fence is the program in its entirety.
  *
  * Hand-over item 8. Fences have been optional in this project since 2026-07-09, but the composer
  * yields ZERO ScalaScript subtrees for a fenceless file, so a whole program reads as prose and
  * nothing says so. v3 works around it by fencing the text before handing it over, which is why
  * this is a request rather than a blocker — but the failure it prevents is silent.
  *
  * MEASURED BEFORE DESIGNING, because the obvious rule is wrong. 89 of 1,189 corpus files carry no
  * fence, and exactly ONE of them is genuinely doc-only — `v1/runtime/std/mapreduce/index.ssc`,
  * which is front matter, headings and link-imports with no code at all. "No fence implies all
  * code" would hand that file's markdown to the ScalaScript dialect, which is precisely the mistake
  * `SpikeTypedCoverageSpec`'s header records: an earlier revision did it corpus-wide and reported
  * 33,487 phantom parse errors, 18,782 of them backticks.
  *
  * So the trigger is BOTH conditions: no ScalaScript fence AND no markdown heading. This spec pins
  * the two populations apart before any of it is wired, and the shape probe below is what tells the
  * implementation where the body actually sits. */
final class SscBareModeSpec extends AnyFunSuite:

  private val bareProgram =
    "val greeting = \"bare\"\nvar total = 0\nprintln(greeting + total)\n"

  private val docOnly =
    "---\nname: std-mapreduce\nexports:\n  - Dataset\n---\n\n# `std.mapreduce`\n\nRe-exports the sub-modules.\n\n## Imports\n\n[Dataset](dataset.ssc)\n"

  private def kinds(n: UniNode): Vector[String] = n match
    case b: UniNode.Branch => b.kind +: b.edges.flatMap(e => kinds(e.child))
    case _                 => Vector.empty

  private def scalaSubtrees(n: UniNode): Vector[UniNode] = n match
    case b: UniNode.Branch =>
      if b.kind.startsWith("spike.") then Vector(b)
      else b.edges.flatMap(e => scalaSubtrees(e.child))
    case _ => Vector.empty

  test("the two populations are distinguishable by a HEADING, and that is the discriminator") {
    // The control for the rule itself. If a heading did not separate them, the rule would be
    // arbitrary and the doc-only file would be parsed as code.
    assert(!kinds(SscCompose.parse(bareProgram).root).exists(_.contains("heading")),
           "the bare program has a heading, so the discriminator does not hold")
    assert(kinds(SscCompose.parse(docOnly).root).exists(_.contains("heading")),
           "the doc-only file has no heading, so the discriminator does not hold")
  }

  test("a fenceless program yields a ScalaScript subtree") {
    val n = scalaSubtrees(SscCompose.parse(bareProgram).root).size
    assert(n == 1, s"a bare .ssc produced $n ScalaScript subtrees — the program read as prose")
  }

  test("a doc-only file yields NO ScalaScript subtree — its markdown is not code") {
    val n = scalaSubtrees(SscCompose.parse(docOnly).root).size
    assert(n == 0, s"a doc-only file produced $n ScalaScript subtrees — its prose was parsed as code")
  }

  test("a fenced file is untouched by bare mode") {
    val fenced = "# Title\n\n```scalascript\nval x = 1\n```\n"
    assert(scalaSubtrees(SscCompose.parse(fenced).root).sizeIs == 1, "the fenced path regressed")
  }

  test("bare mode is LOSSLESS — the tree still reconstructs the file byte for byte") {
    // The property that matters most, and the one the composer has broken before: an injected
    // subtree replacing an interleaved body cannot preserve order. Six corpus files failed exactly
    // that way once.
    val rebuilt = UniNode.sourceTokens(SscCompose.parse(bareProgram).root).map(_.lexeme).mkString
    assert(rebuilt == bareProgram, s"round-trip lost bytes:\nwant [$bareProgram]\ngot  [$rebuilt]")
  }

  test("a doc-only file is lossless too") {
    val rebuilt = UniNode.sourceTokens(SscCompose.parse(docOnly).root).map(_.lexeme).mkString
    assert(rebuilt == docOnly, s"round-trip lost bytes:\nwant [$docOnly]\ngot  [$rebuilt]")
  }

  test("PROBE: which fenceless corpus files does bare mode fail to parse") {
    val root = SscCorpus.repoRoot
    val bad = SscCorpus.paths(root)
      .map(p => (p, SscCompose.parse(SscCorpus.read(p))))
      .filter((_, c) => c.diagnostics.nonEmpty)
      .map((p, c) => (root.relativize(p).toString, c.diagnostics.size))
      .sortBy(-_._2)
    info(s"files with diagnostics: ${bad.size}")
    bad.take(12).foreach((f, n) => info(f"  $n%5d  $f"))
  }
