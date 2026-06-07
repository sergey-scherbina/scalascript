package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.3.3 — jsonParse / jsonStringify via serde_json crate. */
class RustGenR33Test extends AnyFunSuite:

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

  test("Intrinsics map carries jsonParse and jsonStringify"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("jsonParse"))     == RuntimeCall("crate::runtime::_json_parse"))
    assert(ic(QualifiedName("jsonStringify")) == RuntimeCall("crate::runtime::_json_stringify"))

  test("jsonParse pulls serde_json into Cargo.toml; runtime template appended"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(jsonParse("{\"x\":1}"))
        |```
        |""".stripMargin
    )
    val toml = a("Cargo.toml")
    assert(toml.contains("""serde_json = "1.0""""),
      s"serde_json dep not in Cargo.toml:\n$toml")
    val rt = a("src/runtime/mod.rs")
    assert(rt.contains("pub fn _json_parse(input: &str) -> String"))

  test("jsonStringify alone also pulls serde_json"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(jsonStringify("{}"))
        |```
        |""".stripMargin
    )
    assert(a("Cargo.toml").contains("""serde_json = "1.0""""))
    assert(a("src/runtime/mod.rs").contains("pub fn _json_stringify"))

  test("hello-world stays serde_json-free"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin
    )
    assert(!a("Cargo.toml").contains("serde_json"))
    assert(!a("src/runtime/mod.rs").contains("_json_parse"))

  test("JSON call sites borrow their args (& prefix)"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println(jsonParse("{}"))
        |```
        |""".stripMargin
    )
    val gen = a("src/generated/ssc_program.rs")
    assert(gen.contains("crate::runtime::_json_parse(&\"{}\".to_string())"),
      s"borrow-emit for jsonParse not found in:\n$gen")
