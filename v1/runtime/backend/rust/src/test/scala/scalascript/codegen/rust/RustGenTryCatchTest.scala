package scalascript.codegen.rust

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.{BackendOptions, CompileResult, Diagnostic, Segment}
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** `try`/`catch` and `throw` on the Rust target.
 *
 *  ON THIS TARGET AN EXCEPTION IS ITS MESSAGE. Rust has no exception type to carry, so `throw`
 *  becomes `panic!("{}", …)` — whose payload is a `String` — and `catch` downcasts that payload
 *  back. Throw and catch therefore agree by construction rather than by comment, which is the whole
 *  argument for lowering them together instead of one at a time.
 *
 *  These are STRING-MATCH tests. The proof that the emitted Rust actually compiles and runs is in
 *  `RustGenCargoSmokeTest`, which is where this lane's real failures have been found — the file's
 *  own header says a string-match suite cannot see move/borrow errors in valid-LOOKING Rust. */
class RustGenTryCatchTest extends AnyFunSuite:

  private val emptyOpts = BackendOptions(
    baseDir = None, outputDir = None,
    optimizationLevel = 0, emitSourceMaps = false, emitAssertions = false,
    target = None, extra = Map.empty
  )

  private def compile(src: String): Either[String, String] =
    new RustBackend().compile(Normalize(Parser.parse(src)), emptyOpts) match
      case CompileResult.Segmented(segs) =>
        Right(segs.collectFirst {
          case Segment.Asset("src/generated/ssc_program.rs", b, _) => new String(b, "UTF-8")
        }.getOrElse(fail("generated module missing")))
      case CompileResult.Failed(ds) => Left(ds.map { case Diagnostic.Generic(m, _) => m; case d => d.toString }.mkString("; "))
      case other                    => fail(s"expected Segmented or Failed, got $other")

  private def gen(src: String): String =
    compile(src).fold(e => fail(s"expected success, got: $e"), identity)

  private def refusal(src: String): String =
    compile(src).fold(identity, g => fail(s"expected a refusal, got Rust:\n$g"))

  test("a bound catch downcasts the panic payload back to the message"):
    val g = gen(
      """```scalascript
        |def show(): String =
        |  try "ok"
        |  catch case e: Throwable => e
        |```""".stripMargin)
    assert(g.contains("catch_unwind"), g)
    assert(g.contains("AssertUnwindSafe"), g)
    assert(g.contains("let e = __p.downcast_ref::<String>()"), g)

  test("`case _ =>` binds nothing and does not leave an unused variable"):
    val g = gen(
      """```scalascript
        |def show(): String =
        |  try "ok"
        |  catch case _ => "recovered"
        |```""".stripMargin)
    assert(g.contains("catch_unwind"), g)
    assert(!g.contains("downcast_ref"), g)   // nothing was bound, so nothing is decoded
    assert(g.contains("let _ = __p;"), g)    // …and the payload is discarded explicitly

  test("`throw` panics with the message, and `new X(msg)` contributes only its message"):
    val plain = gen(
      """```scalascript
        |def boom(): String = throw "bang"
        |```""".stripMargin)
    // the message is whatever the walker renders the expression as — here `"bang".to_string()`.
    // Asserting the exact rendering would pin an unrelated decision; the property is that the
    // message reaches the panic.
    assert(plain.contains("panic!(\"{}\", \"bang\""), plain)
    val wrapped = gen(
      """```scalascript
        |def boom(): String = throw new RuntimeException("bang")
        |```""".stripMargin)
    assert(wrapped.contains("panic!(\"{}\", \"bang\""), wrapped)
    assert(!wrapped.contains("RuntimeException"), wrapped)   // the class name has no meaning here

  // ── refused BY NAME, so a reader is not left guessing what "unsupported" covered ──────────────

  test("`finally` is refused and says why"):
    val msg = refusal(
      """```scalascript
        |def show(): String =
        |  try "ok"
        |  catch case _ => "recovered"
        |  finally println("done")
        |```""".stripMargin)
    assert(msg.contains("finally"), msg)
    assert(msg.contains("unwinding path"), msg)

  test("several catch arms are refused, naming the count and the reason"):
    val msg = refusal(
      """```scalascript
        |def show(): String =
        |  try "ok"
        |  catch
        |    case e: RuntimeException => "a"
        |    case e: Throwable => "b"
        |```""".stripMargin)
    assert(msg.contains("one `catch` arm"), msg)
    assert(msg.contains("2"), msg)
