package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.3.2 — crypto + base64 intrinsics with per-module Cargo dep
 *  walking. */
class RustGenR32Test extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def assets(src: String): Map[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) => n -> new String(b, "UTF-8") }.toMap
      case other => fail(s"expected Segmented, got $other")

  test("RustCapabilities declares Crypto"):
    val caps = new RustBackend().capabilities.features
    assert(caps.contains(Feature.Crypto))

  test("Intrinsics map carries sha256, base64Encode, base64Decode"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("sha256"))       == RuntimeCall("crate::runtime::_sha256"))
    assert(ic(QualifiedName("base64Encode")) == RuntimeCall("crate::runtime::_base64_encode"))
    assert(ic(QualifiedName("base64Decode")) == RuntimeCall("crate::runtime::_base64_decode"))

  test("hello-world stays dep-free in Cargo.toml"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("Hello")
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(!toml.contains("sha2"),   s"unexpected sha2 dep:\n$toml")
    assert(!toml.contains("base64"), s"unexpected base64 dep:\n$toml")
    assert(!a("src/runtime/mod.rs").contains("pub fn _sha256"))
    assert(!a("src/runtime/mod.rs").contains("pub fn _base64_encode"))

  test("sha256-using program pulls in sha2 only (not base64)"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(sha256("hello"))
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(toml.contains("""sha2 = "0.10""""))
    assert(!toml.contains("base64"))
    val rt = a("src/runtime/mod.rs")
    assert(rt.contains("pub fn _sha256(input: &str) -> String"))
    assert(!rt.contains("pub fn _base64_encode"))

  test("base64-using program pulls in base64 only (not sha2)"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(base64Encode("hi"))
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(toml.contains("""base64 = "0.22""""))
    assert(!toml.contains("sha2"))
    val rt = a("src/runtime/mod.rs")
    assert(rt.contains("pub fn _base64_encode(input: &str) -> String"))
    assert(!rt.contains("pub fn _sha256"))

  test("program using both pulls in both deps"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit =
        |  println(sha256("hello"))
        |  println(base64Decode(base64Encode("hi")))
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(toml.contains("""sha2 = "0.10""""))
    assert(toml.contains("""base64 = "0.22""""))

  test("call sites borrow args (& prefix)"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(sha256("x"))
        |```
        |""".stripMargin
    )
    val gen = a("src/generated/ssc_program.rs")
    assert(gen.contains("crate::runtime::_sha256(&\"x\".to_string())"),
      s"borrow-emit not found in:\n$gen")
