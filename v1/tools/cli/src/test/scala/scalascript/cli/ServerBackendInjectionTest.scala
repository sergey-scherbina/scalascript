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
 *  See `specs/http-server-spi-plan.md` for the SPI design rationale
 *  and the `// ssc compile --server-backend` wiring. */
class ServerBackendInjectionTest extends AnyFunSuite:

  test("jdk — pass-through (no injection)") {
    val src = "println(\"hello\")\n"
    assert(injectServerBackend(src, "jdk") == src)
  }

  test("jetty — adds dep directive at top + init block at bottom") {
    val src = "println(\"hello\")\n"
    val out = injectServerBackend(src, "jetty")
    assert(out.startsWith("//> using repository "))
    assert(out.contains("//> using dep io.scalascript::scalascript-runtime-server-jvm-jetty:"))
    assert(out.contains("println(\"hello\")"))
    assert(out.contains("HttpServerBackends.register(new scalascript.server.jvm.jetty.JettyServerBackend)"))
    assert(out.contains("HttpServerBackends.setBackend(\"jetty\")"))
  }

  test("netty — adds dep directive at top + init block at bottom") {
    val src = "println(\"hello\")\n"
    val out = injectServerBackend(src, "netty")
    assert(out.startsWith("//> using repository "))
    assert(out.contains("//> using dep io.scalascript::scalascript-runtime-server-jvm-netty:"))
    assert(out.contains("HttpServerBackends.register(new scalascript.server.jvm.netty.NettyServerBackend)"))
    assert(out.contains("HttpServerBackends.setBackend(\"netty\")"))
  }

  /** THE DEP ALONE WAS NEVER RESOLVABLE. Nothing from this project is on Maven Central, so the
   *  coordinate needs a repository beside it; these two rows are the ones that would have caught the
   *  defect that shipped. (BUGS.md emitted-server-backend-coordinate-resolves-nowhere.) */
  test("jetty — the dep is accompanied by the repository it resolves from") {
    val out = injectServerBackend("println(1)\n", "jetty")
    val repoIdx = out.indexOf("//> using repository ")
    val depIdx  = out.indexOf("//> using dep ")
    assert(repoIdx >= 0, "no repository directive — the coordinate resolves nowhere without one")
    assert(depIdx > repoIdx, "the repository must be declared before the dep that needs it")
    assert(out.contains(DefaultServerBackendRepo), s"expected the default tree, got:\n$out")
  }

  test("netty — the repository directive is there too") {
    val out = injectServerBackend("println(1)\n", "netty")
    assert(out.contains("//> using repository "))
    assert(out.contains(DefaultServerBackendRepo))
  }

  /** The offline path. Not exercised through the environment here — a unit test that sets a process
   *  env var is a test of the JDK, and the end-to-end row in
   *  `tests/e2e/server-backend-resolvable-gate.sh` runs the real thing with it set. What is checked
   *  here is the property that makes the two paths exclusive: a script may not carry BOTH a jar and
   *  a coordinate for the same backend, or which one wins becomes scala-cli's decision. */
  test("the dep path and the jar path are mutually exclusive by construction") {
    val out = injectServerBackend("println(1)\n", "jetty")
    val hasJar = out.contains("//> using jar ")
    val hasDep = out.contains("//> using dep ")
    assert(hasJar != hasDep, s"exactly one of jar/dep must be emitted, got jar=$hasJar dep=$hasDep")
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
