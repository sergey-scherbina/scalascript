package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Vector/Seq/IndexedSeq/Iterable lower to Rust `vec![…]` (eager immutable sequences, identical to
 *  List in Rust's Vec-backed model), and `seq(i)` on a top-level sequence val lowers to O(1)
 *  `seq[i as usize]` indexing. (collection-vector-indexed.) */
class RustGenCollectionTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def allRust(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) if n.endsWith(".rs") => new String(b, "UTF-8") }.mkString("\n")
      case other => fail(s"expected Segmented, got $other")

  test("Vector / Seq / IndexedSeq / Iterable constructors lower to vec!"):
    val rs = allRust(
      """```scalascript
        |def a(): Vector[Int]     = Vector(1, 2, 3)
        |def b(): Seq[Int]        = Seq(4, 5)
        |def c(): IndexedSeq[Int] = IndexedSeq(6)
        |def d(): Iterable[Int]    = Iterable(7, 8)
        |```
        |""".stripMargin
    )
    // numeric literals carry the i64 suffix in emitted Rust (`vec![1i64, 2i64, 3i64]`).
    assert(rs.contains("vec![1i64, 2i64, 3i64]"), rs)
    assert(rs.contains("vec![4i64, 5i64]"), rs)
    assert(rs.contains("vec![6i64]"), rs)
    assert(rs.contains("vec![7i64, 8i64]"), rs)
    // all four sequence types lower their return to Vec<i64>
    assert(rs.contains("-> Vec<i64>"), rs)

  test("seq(i) on a top-level Vector val lowers to seq[i as usize] indexing"):
    val rs = allRust(
      """```scalascript
        |val v: Vector[Int] = Vector(10, 20, 30)
        |def pick(i: Int): Int = v(i)
        |```
        |""".stripMargin
    )
    assert(rs.contains("v[(") && rs.contains("as usize]"), s"expected Vec indexing, got:\n$rs")
