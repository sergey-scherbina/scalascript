package scalascript.uniml.spike

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*
import java.nio.file.{Files, Paths}

/** P6.0 gate: precedence-through-UniML proofs on the CST, plus emission of the
  * projection + toy sources for the end-to-end run-ir diff harness (spike-verify.sh).
  */
final class ScalaSpikeSpec extends AnyFunSuite:
  private val src = SourceId("memory:spike.scala")

  private def parse(text: String): ParseResult =
    UniML.parse(SourceInput.fromString(src, text), SpikeDialect)

  private def kindOf(n: UniNode): String = n match
    case b: UniNode.Branch => b.kind
    case UniNode.Token(t)  => t.kind

  private def findBranch(n: UniNode, kind: String): Option[UniNode.Branch] = n match
    case b: UniNode.Branch =>
      if b.kind == kind then Some(b)
      else b.edges.iterator.flatMap(e => findBranch(e.child, kind)).nextOption()
    case _ => None

  private def childWithRole(b: UniNode.Branch, role: String): Option[UniNode] =
    b.edges.collectFirst { case UniEdge(Some(r), c) if r == role => c }

  private def defBody(pr: ParseResult): UniNode =
    val prog = pr.roots.collectFirst { case b @ UniNode.Branch("spike.program", _, _, _) => b }.get
    val dfn  = findBranch(prog, "spike.def").get
    childWithRole(dfn, "def.body").get

  // ── the gate: operator precedence is faithfully nested in the CST ────────────

  test("`1 + 2 * 3` nests as add(1, mul(2,3)) — * binds tighter") {
    val pr = parse("def main(): Int = 1 + 2 * 3")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val body = defBody(pr)
    assert(kindOf(body) == "spike.add")
    val add = body.asInstanceOf[UniNode.Branch]
    assert(kindOf(childWithRole(add, "bin.left").get) == "spike.int")
    assert(kindOf(childWithRole(add, "bin.right").get) == "spike.mul")
  }

  test("`1 * 2 + 3` nests as add(mul(1,2), 3) — left-side product") {
    val pr = parse("def main(): Int = 1 * 2 + 3")
    val body = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.add")
    assert(kindOf(childWithRole(body, "bin.left").get) == "spike.mul")
    assert(kindOf(childWithRole(body, "bin.right").get) == "spike.int")
  }

  test("`(1 + 2) * 3` — parens override precedence") {
    val pr = parse("def main(): Int = (1 + 2) * 3")
    val body = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.mul")
    val paren = childWithRole(body, "bin.left").get.asInstanceOf[UniNode.Branch]
    assert(paren.kind == "spike.paren")
    assert(kindOf(childWithRole(paren, "paren.inner").get) == "spike.add")
  }

  test("source order of significant tokens is preserved (lossless up to trivia)") {
    val text = "def main(): Int = 1 * 2 + 3"
    val pr = parse(text)
    val prog = pr.roots.head
    val got = UniNode.sourceTokens(prog).filter(_.kind != "spike.ws").map(_.lexeme).mkString(" ")
    assert(got == "def main ( ) : Int = 1 * 2 + 3", got)
  }

  test("if / call / params parse into the expected frames") {
    val pr = parse("def f(x: Int): Int = if x then g(x, 1 + 2) else 0")
    assert(pr.status == CompletionStatus.Complete, pr.diagnostics)
    val body = defBody(pr).asInstanceOf[UniNode.Branch]
    assert(body.kind == "spike.if")
    val call = findBranch(body, "spike.call").get
    val args = call.edges.collect { case UniEdge(Some("call.arg"), c) => kindOf(c) }
    assert(args == Vector("spike.id", "spike.add"), args)
  }

  // ── emit projection + toy sources for the end-to-end harness ─────────────────

  test("emit projection + toys for run-ir diff") {
    val outDir = sys.env.getOrElse("SPIKE_OUT", "/private/tmp/claude-501/-Users-sergiy-work-my-scalascript/0ae59ae0-0693-4dfa-b393-87f68bf3d01b/scratchpad/p6.0")
    Files.createDirectories(Paths.get(outDir))
    val toys = Seq(
      "add-mul" -> "def main(): Int = 1 + 2 * 3",     // 7
      "mul-add" -> "def main(): Int = 1 * 2 + 3",     // 5
      "paren"   -> "def main(): Int = (1 + 2) * 3",   // 9
      "nested"  -> "def main(): Int = 2 * (3 + 4) - 5" // 9
    )
    for (name, code) <- toys do
      val pr = parse(code)
      val prog = pr.roots.head
      val proj = SpikeProject.program(prog)
      Files.writeString(Paths.get(outDir, s"$name.proj"), proj)
      Files.writeString(Paths.get(outDir, s"$name.toy.ssc"), code + "\n")
    // sentinel so the harness knows emission ran
    Files.writeString(Paths.get(outDir, "EMITTED"), toys.map(_._1).mkString("\n"))
    succeed
  }
