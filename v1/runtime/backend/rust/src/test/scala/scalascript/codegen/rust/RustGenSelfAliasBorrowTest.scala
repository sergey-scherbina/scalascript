package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** The sibling self-alias (`let m = |__a0…| self.m(__a0…);`) must annotate its
 *  reference-typed parameters.
 *
 *  Unannotated, a closure's `&` parameter gets an EARLY-BOUND lifetime tied to the
 *  alias binding, so a temporary passed at a nested call site — a fold lambda handing
 *  `&Vec::new()` through the alias — is rejected with E0716 "temporary value dropped
 *  while borrowed", while the same call direct on `self` compiles. With the declared
 *  type written out (`__a2: &Vec<VmFrame>`) the lifetime is late-bound and the alias
 *  accepts exactly what the method itself accepts.
 *  (v2/BUGS.md `rust-backend-self-alias-closure-rejects-a-temp-borrow`.)
 */
class RustGenSelfAliasBorrowTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def gen(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst {
          case Segment.Asset("src/generated/ssc_program.rs", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("generated module missing"))
      case other => fail(s"expected Segmented, got $other")

  // The reporter's shape (`feature/treevm-top-edges`'s `TreeVm.pushFrame`): a sibling
  // method with a read-only Vec parameter, called from a fold lambda with an empty-vector
  // temporary as that argument.
  private val src =
    """```scalascript
      |case class VmFrame(pc: Int, depth: Int)
      |case class W(stack: Vector[VmFrame], count: Int)
      |
      |case class TreeVm(limit: Int):
      |  private def pushFrame(w: W, f: VmFrame, saved: Vector[VmFrame]): W =
      |    W(w.stack :+ f, w.count + saved.length)
      |
      |  def runAll(w0: W, specs: Vector[Int]): W =
      |    specs.foldLeft(w0)((w, spec) => pushFrame(w, VmFrame(spec, w.count), Vector.empty))
      |
      |def workload(): Int =
      |  TreeVm(9).runAll(W(Vector.empty, 0), Vector(1, 2, 3)).count
      |```
      |""".stripMargin

  test("the self-alias annotates its `&Vec` parameter so a temporary borrow is accepted"):
    val g = gen(src)
    assert(g.contains("let pushFrame = |__a0, __a1, __a2: &Vec<VmFrame>| self.pushFrame(__a0, __a1, __a2);"), g)
    // The call site still borrows the temporary — the annotation, not an argument rewrite,
    // is what carries the fix.
    assert(g.contains("&Vec::new()"), g)
