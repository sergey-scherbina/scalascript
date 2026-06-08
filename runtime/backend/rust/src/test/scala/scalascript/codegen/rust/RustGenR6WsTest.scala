package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.6 — WebSocket server + client via tokio-tungstenite.
 *  Verifies:
 *  - `RustCapabilities` declares `Feature.WebSockets`
 *  - Intrinsics map carries wsRoute, wsServe, wsConnectSync
 *  - hello-world stays ws-dep-free in Cargo.toml
 *  - program with wsRoute pulls in tokio-tungstenite + futures-util + tokio
 *  - program with wsConnectSync also pulls the WS deps
 *  - `src/runtime/ws.rs` is emitted with helper fns
 *  - `src/runtime/mod.rs` re-exports the `ws` submodule
 *  - when HTTP + WS both used, tokio is not duplicated */
class RustGenR6WsTest extends AnyFunSuite:

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

  test("RustCapabilities declares WebSockets"):
    assert(new RustBackend().capabilities.features.contains(Feature.WebSockets))

  test("Intrinsics map carries wsRoute, wsServe, wsConnectSync"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("wsRoute"))       == RuntimeCall("crate::runtime::ws::_ws_route"))
    assert(ic(QualifiedName("wsServe"))       == RuntimeCall("crate::runtime::ws::_ws_serve"))
    assert(ic(QualifiedName("wsConnectSync")) == RuntimeCall("crate::runtime::ws::_ws_connect_sync"))

  test("hello-world stays ws-dep-free in Cargo.toml"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(!toml.contains("tokio-tungstenite"), "tokio-tungstenite should not appear for hello-world")
    assert(!toml.contains("futures-util"),      "futures-util should not appear for hello-world")
    assert(!a.contains("src/runtime/ws.rs"),    "ws.rs should not be emitted for hello-world")
    assert(!a("src/runtime/mod.rs").contains("pub mod ws;"),
      "mod.rs should not re-export ws for hello-world")

  test("program with wsRoute pulls in WS deps"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = wsRoute("/ws", msg => s"echo: $msg")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("tokio-tungstenite"), s"tokio-tungstenite missing in:\n$toml")
    assert(toml.contains("futures-util"),      s"futures-util missing in:\n$toml")
    assert(toml.contains("tokio"),             s"tokio missing in:\n$toml")

  test("program with wsRoute emits src/runtime/ws.rs"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = wsRoute("/ws", msg => msg)
        |```
        |""".stripMargin)
    assert(a.contains("src/runtime/ws.rs"), "ws.rs asset missing")
    val ws = a("src/runtime/ws.rs")
    assert(ws.contains("pub fn _ws_route"),          s"_ws_route missing in:\n$ws")
    assert(ws.contains("pub fn _ws_serve"),          s"_ws_serve missing in:\n$ws")
    assert(ws.contains("pub fn _ws_connect_sync"),   s"_ws_connect_sync missing in:\n$ws")
    assert(ws.contains("tokio_tungstenite"),          s"tokio_tungstenite missing in:\n$ws")

  test("runtime/mod.rs re-exports ws submodule when ws is used"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit = wsServe(8080)
        |```
        |""".stripMargin)
    val m = a("src/runtime/mod.rs")
    assert(m.contains("pub mod ws;"), s"'pub mod ws;' missing in runtime/mod.rs:\n$m")

  test("program with wsConnectSync also pulls WS deps"):
    val a = assets(
      """```scalascript
        |def workload(): Unit = wsConnectSync("ws://localhost:8080/ws", msg => println(msg))
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("tokio-tungstenite"), s"tokio-tungstenite missing in:\n$toml")

  test("program with HTTP + WS does not duplicate tokio"):
    val a = assets(
      """```scalascript
        |@main def run(): Unit =
        |  route("GET", "/", _ => "ok")
        |  wsRoute("/ws", msg => msg)
        |  serve(8080)
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    // tokio should appear exactly once (from HTTP, not added again for WS)
    val tokioCount = "tokio =".r.findAllIn(toml).length
    assert(tokioCount == 1, s"tokio should appear once, got $tokioCount in:\n$toml")
    assert(toml.contains("tokio-tungstenite"), s"tokio-tungstenite missing in:\n$toml")
    assert(toml.contains("hyper"),             s"hyper missing in:\n$toml")
