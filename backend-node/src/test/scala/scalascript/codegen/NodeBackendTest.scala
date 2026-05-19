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
