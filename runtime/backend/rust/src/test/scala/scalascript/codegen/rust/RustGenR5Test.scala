package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** Phase R.5 — tokio + hyper HTTP server bootstrap. */
class RustGenR5Test extends AnyFunSuite:

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

  test("RustCapabilities declares HttpServer"):
    assert(new RustBackend().capabilities.features.contains(Feature.HttpServer))

  test("Intrinsic map carries serve and route"):
    val ic = new RustBackend().intrinsics
    import scalascript.ir.QualifiedName
    assert(ic(QualifiedName("serve")) == RuntimeCall("crate::runtime::http::_http_serve"))
    assert(ic(QualifiedName("route")) == RuntimeCall("crate::runtime::http::_http_route"))

  test("hello-world stays http-dep-free"):
    val a = assets("""```scalascript
        |@main def run(): Unit = println("hi")
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(!toml.contains("tokio"))
    assert(!toml.contains("hyper"))
    assert(!a.contains("src/runtime/http.rs"))
    assert(!a("src/runtime/mod.rs").contains("pub mod http;"))

  test("program with serve() pulls in hyper + tokio deps"):
    val a = assets("""```scalascript
        |@main def run(): Unit =
        |  serve(8080)
        |```
        |""".stripMargin)
    val toml = a("Cargo.toml")
    assert(toml.contains("tokio"))
    assert(toml.contains("hyper"))
    assert(toml.contains("hyper-util"))
    assert(toml.contains("http-body-util"))
    assert(toml.contains("bytes"))

  test("program with serve() emits src/runtime/http.rs + pub mod http"):
    val a = assets("""```scalascript
        |@main def run(): Unit = serve(8080)
        |```
        |""".stripMargin)
    assert(a.contains("src/runtime/http.rs"),
      s"http.rs missing from: ${a.keys.toList.sorted}")
    assert(a("src/runtime/mod.rs").contains("pub mod http;"))
    val http = a("src/runtime/http.rs")
    assert(http.contains("pub fn _http_serve(port: i64)"))
    assert(http.contains("pub fn _http_route("))

  test("HTTP template covers the required types"):
    val t = RustRuntimeTemplates.HttpRs
    assert(t.contains("pub type RouteHandler"))
    assert(t.contains("pub fn _http_route("))
    assert(t.contains("pub fn _http_serve(port: i64)"))
    assert(t.contains("tokio::runtime::Runtime"))
    assert(t.contains("hyper::server::conn::http1"))
    assert(t.contains("service_fn(handle_request)"))
