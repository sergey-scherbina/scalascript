package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize
import org.scalatest.funsuite.AnyFunSuite

/** backend-blocks-p6 — @rust("expr") FFI annotation wiring in RustGen.
 *  An `extern def` annotated @rust("expr") emits a Rust function with the
 *  given expression as the body; $0/$1/… are replaced with param names.
 *  See specs/backend-specific-blocks.md §4 and §6 Phase 6. */
class RustFfiAnnotationTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def generated(src: String): String =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        segs.collect { case Segment.Asset(n, b, _) if n.endsWith(".rs") && n.contains("generated") =>
          new String(b, "UTF-8")
        }.mkString
      case other => fail(s"expected Segmented, got $other")

  // ── @rust("expr") wiring ────────────────────────────────────────────────

  test("@rust extern def: expression emitted as fn body"):
    val out = generated(
      """|# Module
         |
         |```scalascript
         |@rust("std::process::id() as i64")
         |extern def currentPid(): Int
         |
         |def work(): Int = currentPid()
         |```
         |""".stripMargin
    )
    assert(out.contains("fn current_pid") || out.contains("fn currentPid"),
      s"@rust extern def must produce a pub fn; got:\n$out")
    assert(out.contains("std::process::id()"),
      "@rust expression must appear in generated Rust code")

  test("@rust extern def: $0 placeholder substituted with parameter name"):
    val out = generated(
      """|# Module
         |
         |```scalascript
         |@rust("std::path::Path::new($0).exists()")
         |extern def fileExists(path: String): Boolean
         |
         |def work(): Boolean = fileExists("hello.txt")
         |```
         |""".stripMargin
    )
    assert(out.contains("std::path::Path::new(path).exists()"),
      "$0 must be substituted with the param name 'path'")

  test("@rust extern def without @rust: def is skipped (not emitted as broken stub)"):
    val out = generated(
      """|# Module
         |
         |```scalascript
         |extern def unimplementedOp(): Int
         |
         |def work(): Int = 42
         |```
         |""".stripMargin
    )
    assert(!out.contains("fn unimplementedOp") && !out.contains("fn unimplemented_op"),
      "extern def without @rust must not produce a broken fn; got:\n$out")

  test("@rust and @jvm both present: Rust backend uses @rust expression"):
    val out = generated(
      """|# Module
         |
         |```scalascript
         |@jvm("java.util.UUID.randomUUID().toString()")
         |@rust("uuid::Uuid::new_v4().to_string()")
         |extern def randomUuid(): String
         |
         |def work(): String = randomUuid()
         |```
         |""".stripMargin
    )
    assert(out.contains("uuid::Uuid::new_v4().to_string()"),
      "Rust backend must use @rust expression when both @jvm and @rust present")
    assert(!out.contains("randomUUID()"),
      "Rust backend must not emit the @jvm expression")
