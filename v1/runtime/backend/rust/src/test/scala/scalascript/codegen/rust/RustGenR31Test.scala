package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.3.1 — time + filesystem intrinsics. */
class RustGenR31Test extends AnyFunSuite:

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

  private def runtime(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst {
          case Segment.Asset("src/runtime/mod.rs", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("runtime module missing"))
      case other => fail(s"expected Segmented, got $other")

  test("RustCapabilities declares FileSystem"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.FileSystem))

  test("Intrinsics map carries nowMillis, readFile, writeFile"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("nowMillis")) == RuntimeCall("crate::runtime::_now_millis"))
    assert(ic(QualifiedName("readFile"))  == RuntimeCall("crate::runtime::_read_file"))
    assert(ic(QualifiedName("writeFile")) == RuntimeCall("crate::runtime::_write_file"))

  test("Runtime template exposes _now_millis / _read_file / _write_file"):
    // Any module triggers the runtime emit; pick the smallest legal one.
    val r = runtime(
      """```scalascript
        |def noop(): Unit = ()
        |```
        |""".stripMargin
    )
    assert(r.contains("pub fn _now_millis() -> i64"))
    assert(r.contains("pub fn _read_file(path: &str) -> String"))
    assert(r.contains("pub fn _write_file(path: &str, contents: &str)"))

  test("writeFile/readFile call sites borrow their args (&path, &msg)"):
    val src =
      """```scalascript
        |@main def run(): Unit =
        |  val p: String = "/tmp/x.txt"
        |  writeFile(p, "hi")
        |  val back: String = readFile(p)
        |  println(back)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""crate::runtime::_write_file(&p, &"hi".to_string());"""),
      s"borrow-emit for writeFile not found in:\n$g")
    assert(g.contains("crate::runtime::_read_file(&p)"),
      s"borrow-emit for readFile not found in:\n$g")

  test("nowMillis call site emits without borrow (zero-arg)"):
    val src =
      """```scalascript
        |@main def run(): Unit = println(nowMillis())
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::_now_millis()"),
      s"nowMillis emit not found in:\n$g")
