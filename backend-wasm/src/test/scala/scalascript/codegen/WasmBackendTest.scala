package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

class WasmBackendTest extends AnyFunSuite with Matchers:

  private val backend = WasmBackend()

  // ── Identity ────────────────────────────────────────────────────────────

  test("backend id is 'wasm'"):
    backend.id shouldBe "wasm"

  test("backend declares WasmBytecode output"):
    backend.capabilities.outputs should contain(OutputKind.WasmBytecode)

  test("backend declares JavaScriptSource output (JS glue)"):
    backend.capabilities.outputs should contain(OutputKind.JavaScriptSource)

  test("backend accepts scala source language"):
    backend.acceptedSources should contain("scala")

  // ── Block detection ─────────────────────────────────────────────────────

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def moduleScala(code: String) =
    Parser.parse(s"# Test\n\n```scala\n$code\n```\n")

  test("WasmGen.hasBlocks: false for scalascript-only module"):
    WasmGen.hasBlocks(module("val x = 1")) shouldBe false

  test("WasmGen.hasBlocks: true for scala block"):
    WasmGen.hasBlocks(moduleScala("@main def run() = println(42)")) shouldBe true

  test("WasmGen.collectSource: concatenates scala blocks"):
    val src = WasmGen.collectSource(moduleScala("val x = 1\n\nval y = 2"))
    src should include("val x = 1")
    src should include("val y = 2")

  test("WasmGen.collectSource: empty for scalascript-only module"):
    WasmGen.collectSource(module("val x = 1")).isBlank shouldBe true

  // ── Backend.compile with no scala blocks ────────────────────────────────

  test("compile returns Segmented(Nil) when no scala blocks present"):
    val ir = Normalize(module("val x = 42\nprintln(x)"))
    backend.compile(ir, BackendOptions()) shouldBe CompileResult.Segmented(Nil)

  // ── Integration: compile scala blocks to WASM ───────────────────────────
  // Skipped when scala-cli does not support --js-wasm.

  private lazy val hasWasmSupport: Boolean =
    try
      val tmp = os.temp("@main def probe() = ()", suffix = ".sc", deleteOnExit = true)
      val outDir = os.temp.dir(deleteOnExit = true)
      val result = os.proc(
        "scala-cli", "--power", "package", "--js", "--js-emit-wasm",
        "-o", (outDir / "probe").toString, tmp
      ).call(check = false, stderr = os.Pipe)
      !result.err.text().contains("Unrecognized argument")
    catch case _: Throwable => false

  private def runWasm(code: String): Array[Byte] =
    val ir = Normalize(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) =>
        segs.collectFirst { case Segment.Asset(_, bytes, "application/wasm") => bytes }
          .getOrElse(fail("no wasm asset in result"))
      case CompileResult.Failed(diags) =>
        fail(s"compilation failed: ${diags.mkString(", ")}")
      case other =>
        fail(s"unexpected result: ${other.getClass.getSimpleName}")

  test("compile simple scala block produces non-empty WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val bytes = runWasm("@main def run() = println(\"hello wasm\")")
    bytes should not be empty
    // WASM magic number: \0asm
    bytes.take(4) shouldBe Array(0x00, 0x61, 0x73, 0x6d).map(_.toByte)

  test("compile produces JS glue alongside WASM binary"):
    assume(hasWasmSupport, "scala-cli --js-wasm not available")
    val ir = Normalize(Parser.parse("# T\n\n```scala\n@main def run() = println(1)\n```\n"))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) =>
        segs.exists { case Segment.Code("javascript", _) => true; case _ => false } shouldBe true
      case other =>
        fail(s"unexpected: $other")

  test("CapabilityCheck: wasm backend accepts sql blocks (v1.27 Phase 5 — no diagnostic)"):
    // v1.27 Phase 5 — Wasm declares `sql` in `blockLanguages` so the
    // generic `UnknownBlockLanguage` diagnostic no longer fires.  sql
    // blocks are routed through the JS shim that already accompanies
    // the .wasm blob; the Wasm body itself is unaffected.  Phase 6 will
    // add a build-time `UnsupportedJdbcUrl` diagnostic for `jdbc:` URLs
    // on non-JVM targets; until then jdbc: URLs surface a runtime error
    // from `sql-runtime.mjs`'s `UnsupportedJdbcUrl` class.
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
    diags.exists {
      case Diagnostic.UnknownBlockLanguage("sql") => true
      case _                                      => false
    } shouldBe false
