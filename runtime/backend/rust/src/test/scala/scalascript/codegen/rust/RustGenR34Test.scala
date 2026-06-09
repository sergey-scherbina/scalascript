package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.3.4 — args / env / exit intrinsics (pure std). */
class RustGenR34Test extends AnyFunSuite:

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

  test("intrinsic map carries args, env, exit"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("args")) == RuntimeCall("crate::runtime::_args"))
    assert(ic(QualifiedName("env"))  == RuntimeCall("crate::runtime::_env"))
    assert(ic(QualifiedName("exit")) == RuntimeCall("crate::runtime::_exit"))

  test("runtime template always ships _args / _env / _exit"):
    val r = runtime("""```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    assert(r.contains("pub fn _args() -> Vec<String>"))
    assert(r.contains("pub fn _env(name: &str) -> Option<String>"))
    assert(r.contains("pub fn _exit(code: i64) ->"))

  test("env() call site borrows its arg"):
    val src =
      """```scalascript
        |@main def run(): Unit = println(env("HOME"))
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("""crate::runtime::_env(&"HOME".to_string())"""),
      s"borrow-emit for env not found in:\n$g")

  test("args() emits as a zero-arg call"):
    val src =
      """```scalascript
        |@main def run(): Unit = println(args().size)
        |```
        |""".stripMargin
    val g = gen(src)
    assert(g.contains("crate::runtime::_args()"),
      s"args() emit not found in:\n$g")

  test("Cargo.toml stays dep-free when only env/args/exit are used"):
    new RustBackend().compile(Normalize(Parser.parse(
      """```scalascript
        |@main def run(): Unit = println(env("X"))
        |```
        |""".stripMargin
    )), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        val toml = segs.collectFirst {
          case Segment.Asset("Cargo.toml", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("Cargo.toml missing"))
        assert(!toml.contains("sha2"))
        assert(!toml.contains("base64"))
        assert(!toml.contains("serde_json"))
      case other => fail(s"expected Segmented, got $other")
