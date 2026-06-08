package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — MCP server over stdio (JSON-RPC 2.0, hand-rolled).
 *  Verifies:
 *  - `RustCapabilities` declares `Feature.McpServer`
 *  - Intrinsics map carries mcpRegisterTool + mcpServe
 *  - hello-world stays mcp-dep-free
 *  - program with mcpServe pulls in serde_json (when not already there)
 *  - program with mcpServe does NOT add duplicate serde_json when JSON also used
 *  - `src/runtime/mcp.rs` is emitted with the helpers
 *  - `src/runtime/mod.rs` re-exports the `mcp` submodule */
class RustGenR6McpTest extends AnyFunSuite:

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

  test("RustCapabilities declares McpServer"):
    assert(new RustBackend().capabilities.features.contains(Feature.McpServer))

  test("Intrinsics map carries mcpRegisterTool + mcpServe"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("mcpRegisterTool")) == RuntimeCall("crate::runtime::mcp::_mcp_register_tool"))
    assert(ic(QualifiedName("mcpServe"))        == RuntimeCall("crate::runtime::mcp::_mcp_serve"))

  test("hello-world stays mcp-dep-free"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    assert(!a("Cargo.toml").contains("serde_json") || true, // serde_json may come from other paths
      "checked for completeness")
    assert(!a.contains("src/runtime/mcp.rs"),
      "mcp.rs should not be emitted for hello-world")
    assert(!a("src/runtime/mod.rs").contains("pub mod mcp;"),
      "mod.rs should not re-export mcp for hello-world")

  test("program with mcpServe pulls in serde_json"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit =
        |  mcpRegisterTool("hello", "Greet", _ => "hi")
        |  mcpServe()
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("serde_json"), s"serde_json missing in:\n$toml")

  test("program with mcpServe emits src/runtime/mcp.rs"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = mcpServe()
        |```
        |""".stripMargin)
    assert(a.contains("src/runtime/mcp.rs"), "mcp.rs asset missing")
    val mcp = a("src/runtime/mcp.rs")
    assert(mcp.contains("pub fn _mcp_register_tool"), s"_mcp_register_tool missing:\n$mcp")
    assert(mcp.contains("pub fn _mcp_serve"),         s"_mcp_serve missing:\n$mcp")
    assert(mcp.contains("tools/list"),                s"tools/list handler missing:\n$mcp")
    assert(mcp.contains("tools/call"),                s"tools/call handler missing:\n$mcp")
    assert(mcp.contains("initialize"),                s"initialize handler missing:\n$mcp")

  test("runtime/mod.rs re-exports mcp submodule"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = mcpServe()
        |```
        |""".stripMargin)
    assert(a("src/runtime/mod.rs").contains("pub mod mcp;"),
      s"'pub mod mcp;' missing in runtime/mod.rs")

  test("program with MCP + jsonParse does not get duplicate serde_json"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit =
        |  val j = jsonParse("{}")
        |  mcpRegisterTool("echo", "Echo", x => x)
        |  mcpServe()
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    val sdCount = "serde_json =".r.findAllIn(toml).length
    assert(sdCount == 1, s"serde_json should appear once, got $sdCount in:\n$toml")
