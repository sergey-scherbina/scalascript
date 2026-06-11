package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.interpreter.{Interpreter, Value}
import scalascript.ir.{NormalizedModule, QualifiedName}
import scalascript.parser.Parser

/** busi-p3-ratelimit-intrinsic-shadow — user-wins + warning policy.
 *  Spec: specs/intrinsic-shadow-policy.md */
class IntrinsicShadowTest extends AnyFunSuite:

  private def newInterp(): Interpreter =
    Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))

  private def run(interp: Interpreter, code: String): Value =
    interp.run(Parser.parse(s"# Test\n\n```scala\n$code\n```\n"))
    interp.lastResult

  test("user top-level def wins over plugin intrinsic of the same name (native installed first)"):
    val interp = newInterp()
    interp.installPlugins(List(ShadowTestPlugin)) // intrinsic in globals before body runs
    val result = run(interp, """
      def rateLimit(key: String): String = "USER"
      rateLimit("k")
    """)
    assert(result == Value.StringV("USER"))
    assert(interp.intrinsicShadowWarnings.contains("rateLimit"))

  test("user def wins when plugins load AFTER the user def (lazy-load ordering)"):
    val interp = newInterp()
    // No plugin installed yet → user def is a plain global FunV.
    val result = run(interp, """
      def rateLimit(key: String): String = "USER"
      rateLimit("k")
    """)
    assert(result == Value.StringV("USER"))
    // Now plugins load: install must NOT clobber the user binding, and must warn.
    interp.installPlugins(List(ShadowTestPlugin))
    assert(interp.globalsView.get("rateLimit").exists(_.isInstanceOf[Value.FunV]))
    assert(interp.intrinsicShadowWarnings.contains("rateLimit"))

  test("intrinsic resolves normally and no warning when the user does not redefine it"):
    val interp = newInterp()
    interp.installPlugins(List(ShadowTestPlugin))
    val result = run(interp, """rateLimit("k")""")
    assert(result == Value.StringV("INTRINSIC"))
    assert(!interp.intrinsicShadowWarnings.contains("rateLimit"))

  test("a local def named like an intrinsic does not warn and does not shadow the global intrinsic"):
    val interp = newInterp()
    interp.installPlugins(List(ShadowTestPlugin))
    val result = run(interp, """
      def wrap(): String =
        def rateLimit(key: String): String = "LOCAL"
        rateLimit("inner")
      List(wrap(), rateLimit("outer"))
    """)
    assert(result == Value.ListV(List(Value.StringV("LOCAL"), Value.StringV("INTRINSIC"))))
    assert(!interp.intrinsicShadowWarnings.contains("rateLimit"))

object ShadowTestPlugin extends Backend:
  def id: String = "shadow-test"
  def displayName: String = "Shadow Test"
  def spiVersion: String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )
  def acceptedSources: Set[String] = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("test plugin only")))
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("rateLimit") -> NativeImpl((_, args) =>
      args match
        case List(_: String) => Value.StringV("INTRINSIC")
        case _ => throw RuntimeException("rateLimit(key)")
    ),
  )
