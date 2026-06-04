package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit smoke for the `--server-backend <name>` flag's
 *  `injectServerBackend` helper.  Confirms:
 *
 *  - `jdk` passes through unchanged (default; no injection).
 *  - `jetty` prepends a `//> using dep` directive for the right
 *    Maven coordinate + appends an init block that registers the
 *    impl with `HttpServerBackends` and calls `setBackend("jetty")`.
 *  - `netty` does the same for the Netty coord.
 *  - unknown name throws (defense — the CLI's arg parser also
 *    validates upstream).
 *
 *  See `docs/specs/http-server-spi-plan.md` for the SPI design rationale
 *  and the `// ssc compile --server-backend` wiring. */
class ServerBackendInjectionTest extends AnyFunSuite:

  test("jdk — pass-through (no injection)") {
    val src = "println(\"hello\")\n"
    assert(injectServerBackend(src, "jdk") == src)
  }

  test("jetty — adds dep directive at top + init block at bottom") {
    val src = "println(\"hello\")\n"
    val out = injectServerBackend(src, "jetty")
    assert(out.startsWith("//> using dep io.scalascript::scalascript-runtime-server-jvm-jetty:"))
    assert(out.contains("println(\"hello\")"))
    assert(out.contains("HttpServerBackends.register(new scalascript.server.jvm.jetty.JettyServerBackend)"))
    assert(out.contains("HttpServerBackends.setBackend(\"jetty\")"))
  }

  test("netty — adds dep directive at top + init block at bottom") {
    val src = "println(\"hello\")\n"
    val out = injectServerBackend(src, "netty")
    assert(out.startsWith("//> using dep io.scalascript::scalascript-runtime-server-jvm-netty:"))
    assert(out.contains("HttpServerBackends.register(new scalascript.server.jvm.netty.NettyServerBackend)"))
    assert(out.contains("HttpServerBackends.setBackend(\"netty\")"))
  }

  test("unknown name — throws IllegalArgumentException") {
    val ex = intercept[IllegalArgumentException] {
      injectServerBackend("println(1)", "vertx")
    }
    assert(ex.getMessage.contains("vertx"))
  }

  test("dep directive precedes the original script verbatim") {
    val src = "object MyApp { def main(a: Array[String]): Unit = println(42) }\n"
    val out = injectServerBackend(src, "jetty")
    val origIdx = out.indexOf("object MyApp")
    val depIdx  = out.indexOf("//> using dep")
    assert(depIdx >= 0 && origIdx > depIdx, s"expected //> using to come before user code (got dep@$depIdx, user@$origIdx)")
  }
