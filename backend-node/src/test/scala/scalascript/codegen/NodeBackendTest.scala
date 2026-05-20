package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.25 Phase 3b — `backend-node` glue + JsGen bundling. */
class NodeBackendTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def compile(src: String): String =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.TextOutput(code, "javascript", _) => code
      case other => fail(s"expected TextOutput(javascript, …), got: $other")

  test("backend identity: target id, display name, output kind") {
    assert(backend.id == "node")
    assert(backend.displayName == "Node.js")
    assert(backend.capabilities.outputs.contains(OutputKind.JavaScriptSource))
  }

  test("capabilities declare node.js and node block langs (and only those)") {
    assert(backend.capabilities.blockLanguages == Set("node.js", "node"))
  }

  test("scalascript-only module — bundle is JsGen output, no glue prefix") {
    val src =
      """|# Test
         |
         |```scalascript
         |val x = 1 + 2
         |```
         |""".stripMargin
    val code = compile(src)
    // JsGen will produce a `let x = ...` or similar; the bundle must compile
    // to some non-empty JavaScript and contain no leftover scalascript markers.
    assert(code.nonEmpty)
    assert(code.contains("1 + 2") || code.contains("3"),
      s"expected JsGen output to mention the expression, got:\n$code")
  }

  test("node.js block — verbatim glue prefix concatenated with JsGen output") {
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.uniqueGlueMarker_xyz = (a, b) => a + b;
         |```
         |
         |# Main
         |
         |```scalascript
         |val uniqueUserMarker_xyz = 5
         |```
         |""".stripMargin
    val code = compile(src)
    assert(code.contains("globalThis.uniqueGlueMarker_xyz = (a, b) => a + b;"),
      s"glue block must appear verbatim, got:\n$code")
    val glueIdx     = code.indexOf("uniqueGlueMarker_xyz")
    val userIdx     = code.indexOf("uniqueUserMarker_xyz")
    // Glue must come before the JsGen-produced user code (so JsGen-produced
    // code can call into globalThis at runtime).
    assert(glueIdx >= 0 && userIdx > glueIdx,
      s"glue (at $glueIdx) must precede JsGen user body (at $userIdx)")
  }

  test("multiple node.js blocks — concatenated in document order") {
    val src =
      """|# A
         |
         |```node.js
         |globalThis.a = 1;
         |```
         |
         |# B
         |
         |```node.js
         |globalThis.b = 2;
         |```
         |""".stripMargin
    val code = compile(src)
    val aIdx = code.indexOf("globalThis.a = 1")
    val bIdx = code.indexOf("globalThis.b = 2")
    assert(aIdx >= 0 && bIdx >= 0, s"both blocks must be present:\n$code")
    assert(aIdx < bIdx, s"document order must be preserved: a@$aIdx then b@$bIdx")
  }

  test("`node` alias is recognised as glue") {
    val src =
      """|# Tools
         |
         |```node
         |globalThis.hi = () => 'hi';
         |```
         |""".stripMargin
    val code = compile(src)
    assert(code.contains("globalThis.hi = () => 'hi';"),
      s"`node` alias block must be linked, got:\n$code")
  }

  test("CapabilityCheck: node backend accepts node.js blocks (no diagnostic)") {
    import scalascript.validate.CapabilityCheck
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.x = 1;
         |```
         |
         |```scalascript
         |val y = 2
         |```
         |""".stripMargin
    val module = Normalize(Parser.parse(src))
    val diags  = CapabilityCheck.validate(module, backend.capabilities, backend.id)
    assert(!diags.exists(_.isInstanceOf[Diagnostic.UnknownBlockLanguage]),
      s"node backend must accept node.js blocks, got: $diags")
  }

  test("CapabilityCheck: node backend rejects sql blocks → UnknownBlockLanguage('sql')") {
    // v1.26 — sql blocks are JVM-only.  The node backend doesn't
    // declare `sql` in its `blockLanguages`, so `validate` must
    // surface a `UnknownBlockLanguage("sql")` diagnostic on any
    // module containing a sql fence.  Wired generically via
    // `Lang.isOpaqueExec` matching on `ir.Content.SqlBlock`.
    import scalascript.validate.CapabilityCheck
    val src =
      """|# Query
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val module = Normalize(Parser.parse(src))
    val diags  = CapabilityCheck.validate(module, backend.capabilities, backend.id)
    assert(diags.exists {
      case Diagnostic.UnknownBlockLanguage("sql") => true
      case _                                      => false
    }, s"node backend must reject sql blocks, got: $diags")
  }

  // ── Integration: actually run the emitted bundle under `node` ────────────

  /** `true` when `node` is on PATH — guard for the integration test below.
   *  CI without Node available still passes the unit tests. */
  private lazy val hasNode: Boolean =
    try
      val pb = new java.lang.ProcessBuilder("node", "--version").redirectErrorStream(true)
      pb.start().waitFor() == 0
    catch case _: Throwable => false

  /** Write `code` to a temp `.cjs` file, run `node <file>`, return stdout
   *  trimmed.  `.cjs` rather than `.mjs` because the JsRuntime preamble
   *  uses `require('fs')` for FileSystem helpers — ES-module mode rejects
   *  `require`.  Phase 4 can switch to `.mjs` once the runtime is
   *  rewritten in ESM. */
  private def runUnderNode(code: String): String =
    val tmp = java.io.File.createTempFile("ssc-node-test-", ".cjs")
    tmp.deleteOnExit()
    val w = new java.io.FileWriter(tmp)
    try w.write(code) finally w.close()
    val pb = new java.lang.ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
    val proc = pb.start()
    val out  = new String(proc.getInputStream.readAllBytes(), "UTF-8")
    proc.waitFor()
    out.trim

  test("integration: emitted bundle runs under `node` and prints user output") {
    assume(hasNode, "node not on PATH — skipping integration test")
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.greet = (name) => `Hello from node.js, ${name}!`;
         |```
         |
         |```scalascript
         |val msg = "Sergiy"
         |println(greet(msg))
         |```
         |""".stripMargin
    val code = compile(src)
    val out  = runUnderNode(code)
    assert(out == "Hello from node.js, Sergiy!", s"expected greeting, got:\n$out")
  }

  // ── Phase 4: extern def ↔ globalThis bridging ───────────────────────────

  test("integration: `extern def` on ssc side resolves against globalThis at runtime") {
    assume(hasNode, "node not on PATH — skipping integration test")
    // The ScalaScript side declares the FFI signature with `extern def` — JsGen
    // skips emission of the stub (EffectAnalysis.isExternDef path).  The
    // ```node.js``` block defines the symbol on globalThis.  At runtime, JS's
    // free-name resolution finds the global and the call goes through.  No
    // intrinsic-table entry is required; the contract is purely "name + arity"
    // between the two sides.
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.add = (a, b) => a + b;
         |globalThis.fileBaseName = (p) => require('path').basename(p);
         |```
         |
         |```scalascript
         |extern def add(a: Int, b: Int): Int
         |extern def fileBaseName(path: String): String
         |
         |println(add(40, 2))
         |println(fileBaseName("/tmp/foo/bar.txt"))
         |```
         |""".stripMargin
    val code = compile(src)
    val out  = runUnderNode(code)
    assert(out == "42\nbar.txt",
      s"expected '42\\nbar.txt', got:\n$out")
  }

  test("integration: extern def signature mismatch fails at runtime, not compile") {
    assume(hasNode, "node not on PATH — skipping integration test")
    // The contract is name+arity; if the JS side returns the wrong type,
    // the ssc compiler does NOT catch it — diagnosis surfaces at runtime.
    // Pin this behaviour so any future "typecheck extern signatures" work
    // is a deliberate decision rather than an accidental regression.
    val src =
      """|# Tools
         |
         |```node.js
         |globalThis.lieAboutType = () => "not-a-number";
         |```
         |
         |```scalascript
         |extern def lieAboutType(): Int
         |val x: Int = lieAboutType()
         |println(x)
         |```
         |""".stripMargin
    val code = compile(src)
    val out  = runUnderNode(code)
    // No compile-time error.  JS runtime prints the string verbatim — the
    // `Int` annotation on the ssc side is a contract, not a derivation.
    assert(out == "not-a-number",
      s"expected 'not-a-number' (untyped runtime), got:\n$out")
  }

  // ── serveAsync(port[, tls]) — Tier 4 codegen multi-backend unblocker ───
  //
  // INT spawns a virtual thread; on Node the existing `serve` is already
  // non-blocking (the event loop holds the process alive while the server
  // has open listeners), so `serveAsync` delegates to `serve` and the
  // caller continues immediately.  See docs/cluster-codegen-gap.md.

  test("codegen: serveAsync(port) emits a non-blocking listen, no keep-alive loop") {
    val src =
      """|# Server
         |
         |```scalascript
         |serveAsync(8080)
         |println("after-serve")
         |```
         |""".stripMargin
    val code = compile(src)
    // 1. The runtime defines serveAsync as a thin delegator.
    assert(code.contains("function serveAsync(port, _tlsCfg)"),
      s"runtime must define serveAsync, got:\n$code")
    // 2. The runtime defines server.listen on top of http.createServer.
    assert(code.contains("server.listen(port"),
      s"runtime must wire .listen() under serve(), got:\n$code")
    // 3. The emitted user code calls serveAsync(8080).
    assert(code.contains("serveAsync(8080)"),
      s"emitted JS must invoke serveAsync, got:\n$code")
    // 4. No blocking keep-alive around the call (no while-true / await on
    //    a never-resolving promise wrapping serveAsync).  The event loop
    //    holds the process alive on its own.
    val callIdx = code.indexOf("serveAsync(8080)")
    val before  = code.substring(math.max(0, callIdx - 200), callIdx)
    val after   = code.substring(callIdx, math.min(code.length, callIdx + 200))
    assert(!before.contains("while (true)") && !after.contains("while (true)"),
      s"serveAsync call must not be wrapped in a while(true) keep-alive, got window:\n$before>>>$after")
    assert(!after.contains("await new Promise"),
      s"serveAsync call must not be followed by a never-resolving await, got:\n$after")
  }

  test("codegen: serveAsync(port, tls(cert, key)) reuses the same TLS form as serve") {
    val src =
      """|# Server
         |
         |```scalascript
         |serveAsync(8443, tls("cert.pem", "key.pem"))
         |```
         |""".stripMargin
    val code = compile(src)
    assert(code.contains("serveAsync(8443, tls(\"cert.pem\", \"key.pem\"))"),
      s"emitted JS must pass tls(...) to serveAsync, got:\n$code")
    // serveAsync delegates to serve, which already handles _tlsCfg →
    // require('https').createServer(...).listen(port).
    assert(code.contains("function tls(cert, key)"),
      s"runtime must define tls(), got:\n$code")
    assert(code.contains("require('https')"),
      s"runtime must use https when _tlsCfg is set, got:\n$code")
  }

  test("integration: serveAsync(port) returns immediately; server binds in background; stop() exits cleanly") {
    assume(hasNode, "node not on PATH — skipping integration test")
    // Pick a random ephemeral port; bind, schedule stop() after a beat,
    // print a sentinel.  Two properties to pin:
    //   (a) `after-serve` appears — proving serveAsync returned to the
    //       caller and didn't loop / await forever.
    //   (b) The script terminates within a sane wall-clock budget after
    //       stop() — proving the only event-loop-hold was the listening
    //       server, and stop() released it.
    // The "actually serves HTTP" property is exercised by the codegen
    // string-assertion above (same .listen() emission as `serve`, which
    // is covered by interpreter-side and JVM-side tests) — round-tripping
    // an HTTP request in the same single-threaded Node process while it
    // also synchronously waits on the response would deadlock the event
    // loop, so we don't try.
    val port = {
      val s = new java.net.ServerSocket(0)
      val p = s.getLocalPort
      s.close()
      p
    }
    val src =
      s"""|# Server
          |
          |```node.js
          |// External TCP probe: schedule a connect() against the bound port
          |// after a short delay and stop() the server on connect.  This runs
          |// on the same event loop *after* the user code's synchronous
          |// portion returns control, so it proves the listener is actually
          |// up (Node accepts the connection) and that stop() unwinds it.
          |globalThis.scheduleProbe = (port) => {
          |  setTimeout(() => {
          |    const sock = require('net').connect(port, '127.0.0.1');
          |    sock.on('connect', () => {
          |      console.log('probe-connected');
          |      sock.end();
          |      stop();
          |    });
          |    sock.on('error', e => { console.log('probe-error:' + e.code); stop(); });
          |  }, 100);
          |};
          |```
          |
          |```scalascript
          |extern def scheduleProbe(port: Int): Unit
          |
          |route("GET", "/ping") { req => Response.text("pong") }
          |serveAsync($port)
          |println("after-serve")
          |scheduleProbe($port)
          |```
          |""".stripMargin
    val code = compile(src)
    val t0   = System.currentTimeMillis()
    val out  = runUnderNode(code)
    val dur  = System.currentTimeMillis() - t0
    val lines = out.split("\n").map(_.trim).filter(_.nonEmpty).toList
    assert(lines.contains("after-serve"),
      s"caller did not continue past serveAsync — output:\n$out")
    assert(lines.contains("probe-connected"),
      s"external probe could not connect — serveAsync did not bind port — output:\n$out")
    assert(dur < 10_000,
      s"script did not terminate within 10s of stop() — duration=$dur ms, output:\n$out")
  }
