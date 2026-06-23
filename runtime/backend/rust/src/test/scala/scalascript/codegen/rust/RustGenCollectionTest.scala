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

  // An index *read* on a non-Copy element seq (`Vec<String>` from `.split`/`.toList`) must
  // `.clone()` — Rust moves out of an `Index` otherwise (E0507: cannot move out of index of
  // `Vec<String>`). Regression for the `indexable split/toList` follow-on (caught by an
  // end-to-end cargo smoke; string-match tests alone missed it).
  test("index read on a String seq clones the element (no move out of Index)"):
    val rs = allRust(
      """```scalascript
        |def pick(): String =
        |  val parts: List[String] = "a,b,c".split(",").toList
        |  parts(1)
        |```
        |""".stripMargin
    )
    assert(rs.contains("parts[(1i64) as usize].clone()"),
      s"expected a cloned index read, got:\n$rs")

  test("index read on a List parameter lowers to Vec indexing"):
    val rs = allRust(
      """```scalascript
        |def first(xs: List[String]): String = xs(0)
        |```
        |""".stripMargin
    )
    assert(rs.contains("xs[(0i64) as usize].clone()"),
      s"expected a cloned parameter index read, got:\n$rs")

  // …but an index *store* `a(i) = v` keeps the target BARE — you can't assign to a clone.
  test("index store on a mutable array stays bare (no .clone() on the target)"):
    val rs = allRust(
      """```scalascript
        |def set(): Int =
        |  val a = Array(1, 2, 3)
        |  a(0) = 9
        |  a(0)
        |```
        |""".stripMargin
    )
    assert(rs.contains("a[(0i64) as usize] = 9i64"),
      s"expected a bare index store, got:\n$rs")
    assert(!rs.contains("as usize].clone() ="),
      s"the store target must not be cloned, got:\n$rs")

  // A reference to a top-level `val` must use the `let`-bound name, not re-inline the
  // collection literal — otherwise `xs.foreach` inside a loop rebuilt the whole `vec!`
  // on every iteration (pattern-match-heavy 4.16→0.32 ms, list-fold 0.153→0.069 ms).
  test("top-level val used in a loop references the binding, not the re-inlined literal"):
    val rs = allRust(
      """```scalascript
        |val xs: List[Int] = List(1, 2, 3)
        |def inc(x: Int): Int = x + 1
        |def run(): Long =
        |  var sum = 0L
        |  var i = 0
        |  while i < 1000 do
        |    xs.foreach(x => { sum = sum + inc(x) })
        |    i = i + 1
        |  sum
        |```
        |""".stripMargin
    )
    // bound once at the top of the def body…
    assert(rs.contains("let xs = vec![1i64, 2i64, 3i64];"), s"expected the topVal binding, got:\n$rs")
    assert(
      rs.sliding("let xs = vec![1i64, 2i64, 3i64];".length).count(_ == "let xs = vec![1i64, 2i64, 3i64];") == 1,
      s"expected the topVal binding only in defs that reference it, got:\n$rs"
    )
    // …and the loop iterates the BINDING, not a fresh literal.
    assert(rs.contains("for x in xs.iter()"), s"expected the foreach to reference `xs`, got:\n$rs")
    assert(!rs.contains("for x in vec!"), s"the loop must NOT re-inline the vec! literal, got:\n$rs")
    assert(!rs.contains("inc(x.clone())"), s"single-use foreach params should not be cloned, got:\n$rs")

  test("function-reference map over a named List-returning def does not lower as Either"):
    val rs = allRust(
      """```scalascript
        |def roomLineName(l: String): String = l.trim
        |def roomStatusLines(): List[String] = List("demo", "rozum")
        |def roomNames(): List[String] = roomStatusLines().map(roomLineName).toList
        |```
        |""".stripMargin
    )
    assert(rs.contains("roomStatusLines().iter().cloned().map(roomLineName).collect::<Vec<_>>()"),
      s"expected ordinary Vec map with a function reference, got:\n$rs")
    assert(!rs.contains("Either::Left"), s"List-returning def must not be treated as Either:\n$rs")
    assert(!rs.contains("Either::Right"), s"List-returning def must not be treated as Either:\n$rs")
