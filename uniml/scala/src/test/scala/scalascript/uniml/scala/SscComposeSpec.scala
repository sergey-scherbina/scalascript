package scalascript.uniml.scala

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.CommonMarkDialect
import scalascript.uniml.spike.{SpikeDialect, SpikeProject}
import java.nio.file.{Files, Paths}

/** a fresh-named dialect used only to prove a user can extend the built-in registry set.
  * It delegates parsing to CommonMark — the point is the registry drives injection by name. */
private object MermaidDialect extends DialectAdapter:
  def id: String = "diagram.mermaid"
  override val aliases: Set[String] = Set("mermaid")
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    CommonMarkDialect.instructions(source)

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

  test("a fence naming no registered dialect stays inert — no dialect reinterprets its bytes") {
    val mixed =
      """|```python
         |print("hi")
         |```
         |```scalascript
         |def main(): Int = 1
         |```
         |""".stripMargin
    val c = SscCompose.parse(mixed)
    assert(c.fences.count(_.injected) == 1, "only the scalascript fence is injected")
    assert(c.fences.count(f => !f.injected) == 1, "the unregistered python fence stays inert")
    assert(c.fences.exists(f => f.lang == "python" && f.dialectId.isEmpty))
    // the inert fence keeps its raw code leaves (nothing spliced under it)
    val pyBlock = allBranches(c.root, "markdown.code-block").find(b => childWithRole(b, "scalascript").isEmpty).get
    assert(pyBlock.edges.exists(_.role.contains("code")), "inert fence still carries its markdown code leaves")
  }

  // ── the registry hook: fence language → dialect is resolved through DialectRegistry ─────────

  test("registry hook: a built-in ```json fence is injected via the registry (a json subtree)") {
    val c = SscCompose.parse(
      """|```json
         |{"a": 1}
         |```
         |""".stripMargin
    )
    val block = findBranch(c.root, "markdown.code-block").get
    assert(childWithRole(block, "json").exists(_.isInstanceOf[UniNode.Branch]), "json subtree spliced in")
    assert(c.fences.exists(f => f.dialectId.contains("json.rfc8259") && f.injected && !f.isScala))
  }

  test("registry hook: builtins resolve the four built-in languages, and only those") {
    val r = SscCompose.builtins
    assert(r.get("scalascript").exists(_.id == "scalascript.spike"))
    assert(r.get("scala").exists(_.id == "scalascript.spike"))
    assert(r.get("yaml").exists(_.id == "yaml.1.2.2"))
    assert(r.get("json").exists(_.id == "json.rfc8259"))
    assert(r.get("markdown").exists(_.id == "markdown.commonmark.0.31.2"))
    assert(r.get("python").isEmpty, "an unregistered language does not resolve")
  }

  test("user-closed: a built-in name cannot be overridden, but a fresh dialect extends the set") {
    // re-registering a built-in (same names) is rejected — the built-in set is closed.
    assert(SscCompose.registryWith(SpikeDialect).isLeft, "cannot re-register a built-in name")
    // a fresh-named dialect registers and then drives fence injection.
    val r = SscCompose.registryWith(MermaidDialect)
    assert(r.isRight)
    val c = SscCompose.parse(
      """|```mermaid
         |graph TD; A-->B
         |```
         |""".stripMargin,
      r.toOption.get,
    )
    assert(c.fences.exists(f => f.lang == "mermaid" && f.dialectId.contains("diagram.mermaid") && f.injected))
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
