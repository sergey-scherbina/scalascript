package scalascript.uniml.scala

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.spike.SpikeProject
import java.nio.file.{Files, Paths}

/** P6.3 — "unify the hybrid". Proves a whole `.ssc` (YAML front-matter + Markdown
  * prose + fenced ScalaScript) parses as ONE lossless UniML tree, and emits the
  * code half for the differential CoreIR harness (specs/v2.2-p6.0-spike-verify.sh):
  * each composed toy's *spliced* ScalaScript subtree is projected to ssc0 and must
  * yield Core IR byte-identical to `ssc1-front` on the extracted bare source.
  */
final class SscComposeSpec extends AnyFunSuite:
  private def findBranch(n: UniNode, kind: String): Option[UniNode.Branch] = n match
    case b: UniNode.Branch =>
      if b.kind == kind then Some(b)
      else b.edges.iterator.flatMap(e => findBranch(e.child, kind)).nextOption()
    case _ => None

  private def allBranches(n: UniNode, kind: String): Vector[UniNode.Branch] = n match
    case b: UniNode.Branch =>
      val here = if b.kind == kind then Vector(b) else Vector.empty
      here ++ b.edges.flatMap(e => allBranches(e.child, kind))
    case _ => Vector.empty

  private def childWithRole(b: UniNode.Branch, role: String): Option[UniNode] =
    b.edges.collectFirst { case UniEdge(Some(r), c) if r == role => c }

  // a complete .ssc: YAML front-matter, Markdown prose, and a fenced program.
  private val basic =
    """|---
       |title: demo
       |version: 2
       |---
       |# Adder
       |
       |Some prose about the program.
       |
       |```scalascript
       |def main(): Int = 2 + 3 * 4
       |```
       |""".stripMargin

  test("a whole .ssc parses as ONE tree: front-matter + prose + code, all under ssc.file") {
    val c = SscCompose.parse(basic)
    assert(c.status == CompletionStatus.Complete, c.diagnostics)
    val root = c.root.asInstanceOf[UniNode.Branch]
    assert(root.kind == "ssc.file")
    assert(findBranch(root, "markdown.front-matter").nonEmpty, "front-matter framed")
    assert(findBranch(root, "markdown.heading").nonEmpty, "prose framed")
    assert(findBranch(root, "markdown.code-block").nonEmpty, "code framed")
  }

  test("the fenced ScalaScript is really parsed (spike.program spliced in, not inert text)") {
    val c = SscCompose.parse(basic)
    val code = findBranch(c.root, "markdown.code-block").get
    val injected = childWithRole(code, "scalascript")
    assert(injected.exists { case b: UniNode.Branch => b.kind == "spike.program"; case _ => false })
    // operator precedence survived the whole hybrid pipeline: +(2, *(3,4))
    assert(findBranch(injected.get, "spike.infix").nonEmpty)
  }

  test("the YAML front-matter is really parsed (a yaml subtree is spliced in)") {
    val c = SscCompose.parse(basic)
    val fm = findBranch(c.root, "markdown.front-matter").get
    assert(childWithRole(fm, "yaml").exists(_.isInstanceOf[UniNode.Branch]))
    assert(c.frontMatter.exists(_.contains("title")))
  }

  test("extraction is lossless: scalaSource is exactly the fence body (the bare .ssc0)") {
    assert(SscCompose.parse(basic).scalaSource == "def main(): Int = 2 + 3 * 4")
  }

  test("multiple ```scalascript fences compose: two blocks, both injected, one program's source") {
    val multi =
      """|# Two blocks
         |```scalascript
         |case class Point(x: Int, y: Int)
         |```
         |Prose between.
         |```scalascript
         |def main(): Int = Point(3, 4).x
         |```
         |""".stripMargin
    val c = SscCompose.parse(multi)
    val blocks = allBranches(c.root, "markdown.code-block")
    assert(blocks.size == 2)
    assert(blocks.forall(b => childWithRole(b, "scalascript").nonEmpty))
    assert(c.scalaSource == "case class Point(x: Int, y: Int)\ndef main(): Int = Point(3, 4).x")
  }

  test("a foreign fence is left inert — no dialect reinterprets another's bytes") {
    val mixed =
      """|```json
         |{"a": 1}
         |```
         |```scalascript
         |def main(): Int = 1
         |```
         |""".stripMargin
    val c = SscCompose.parse(mixed)
    assert(c.fences.count(_.injected) == 1)
    assert(c.fences.count(f => !f.injected) == 1)
    val foreign = allBranches(c.root, "markdown.code-block").find(b => childWithRole(b, "scalascript").isEmpty)
    assert(foreign.nonEmpty, "the json fence stays a plain markdown.code-block")
  }

  // ── emit differential-harness files for the composed .ssc toys ──────────────
  // Single-fence toys: the fence body is a whole program, so the spliced subtree
  // projects directly. .proj comes from the subtree INSIDE the composed .ssc tree.
  private val toys = Seq(
    "hybrid-basic" ->
      """|---
         |title: demo
         |---
         |# Adder
         |```scalascript
         |def main(): Int = 2 + 3 * 4
         |```
         |""".stripMargin,
    "hybrid-cc" ->
      """|# Points
         |```scalascript
         |case class Point(x: Int, y: Int)
         |def main(): Int = Point(3, 4).x + Point(3, 4).y
         |```
         |""".stripMargin,
  )

  test("emit hybrid harness toys (composed .ssc → spliced subtree → .proj + bare .toy.ssc)") {
    val outDir = sys.env.getOrElse("SPIKE_OUT", "/tmp")
    Files.createDirectories(Paths.get(outDir))
    for (name, ssc) <- toys do
      val c = SscCompose.parse(ssc)
      assert(c.status == CompletionStatus.Complete, s"$name: ${c.diagnostics}")
      val code = findBranch(c.root, "markdown.code-block").get
      val injected = childWithRole(code, "scalascript").get // the spike.program spliced INTO the .ssc tree
      Files.writeString(Paths.get(outDir, s"$name.proj"), SpikeProject.program(injected))
      Files.writeString(Paths.get(outDir, s"$name.toy.ssc"), c.scalaSource + "\n")
      Files.deleteIfExists(Paths.get(outDir, s"$name.expect"))
  }
