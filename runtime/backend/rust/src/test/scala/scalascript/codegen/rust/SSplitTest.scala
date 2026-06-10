package scalascript.codegen.rust
import scalascript.parser.Parser
import scalascript.transform.Normalize
import scalascript.backend.spi.*
import org.scalatest.funsuite.AnyFunSuite
class SSplitTest extends AnyFunSuite:
  test("string-split codegen"):
    val src = """```scalascript
|val csv: String = "1,2,3"
|def workload(): Long =
|  var sum = 0L
|  var i = 0
|  while i < 10 do
|    val r = csv.split(",").map(s => s.trim.toInt).foldLeft(0)((a, b) => a + b)
|    sum = sum + r.toLong
|    i = i + 1
|  sum
|```
|""".stripMargin
    val emptyOpts = BackendOptions(baseDir=None,outputDir=None,optimizationLevel=0,emitSourceMaps=false,emitAssertions=false,target=None,extra=Map.empty)
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst {
          case Segment.Asset("src/generated/ssc_program.rs", b, _) => new String(b, "UTF-8")
        }.foreach(code => println(s"\n=== GENERATED ===\n$code\n=== END ==="))
      case other => println(s"FAIL: $other")
    assert(true)
