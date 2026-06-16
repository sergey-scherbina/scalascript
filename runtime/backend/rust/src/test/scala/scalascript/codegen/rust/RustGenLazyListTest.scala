package scalascript.codegen.rust
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite
class RustGenLazyListTest extends AnyFunSuite:
  private val o = BackendOptions(None, None, 0, false, false, None, Map.empty)
  private def rs(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), o) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) if n.endsWith(".rs") => new String(b, "UTF-8") }.mkString("\n")
      case other => fail(s"$other")
  test("LazyList chains lower to lazy iterators"):
    val r = rs("""```scalascript
      |def a(): Long = LazyList.from(1).map(x => x * 2).take(8).sum
      |def b(): Vector[Int] = LazyList.from(1).filter(x => x % 2 == 0).take(3).toList
      |def c(): Long = LazyList.iterate(1)(x => x * 2).take(5).sum
      |```
      |""".stripMargin)
    // LazyList lowers to lazy std iterators forced at .sum/.toList. (lazylist-all-backends.)
    info(r.linesIterator.filter(l => l.contains("..") || l.contains("successors") || l.contains(".take") || l.contains(".sum")).map(_.trim).mkString(" || "))
    assert(r.contains("(1i64..)"), r)
    assert(r.contains(".take(8i64 as usize)"), r)
    assert(r.contains(".sum::<i64>()"), r)
    assert(r.contains("successors"), r)
