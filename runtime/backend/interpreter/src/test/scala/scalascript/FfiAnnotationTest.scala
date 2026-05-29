package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.parser.Parser
import scalascript.validate.CapabilityCheck
import scalascript.backend.spi.{Capabilities, Feature, OutputKind, Diagnostic, SpiVersionRange, SpiVersion}
import scalascript.ir.NormalizedModule
import scalascript.transform.Normalize

/** arch-ffi-p1 — @jvm / @js inline FFI annotation tests.
 *
 *  Verifies that:
 *  - JvmGen emits the @jvm("expr") body instead of skipping the extern def.
 *  - $N argument substitution replaces placeholders with parameter names.
 *  - JsGen emits the @js("expr") body.
 *  - @jvm-only (no @js) extern defs produce a throwing JS stub.
 *  - CapabilityCheck detects @jvm-only defs when compiling for JS target.
 */
class FfiAnnotationTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def jvm(code: String): String = JvmGen.generate(module(code))
  private def js(code: String): String  = JsGen.generate(module(code))

  // ─── JvmGen — @jvm inline body ──────────────────────────────────────────

  test("JvmGen: @jvm extern def emits inline expression as method body"):
    val out = jvm(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    out should include ("def randomUUID")
    out should include ("java.util.UUID.randomUUID().toString()")
    out should not include ("__extern__")

  test("JvmGen: @jvm extern def with $0 substitution replaces placeholder with param name"):
    val out = jvm(
      """@jvm("new java.io.File($0).exists()")
        |extern def fileExists(path: String): Boolean
        |""".stripMargin
    )
    out should include ("def fileExists")
    out should include ("new java.io.File(path).exists()")

  test("JvmGen: @jvm extern def with multiple arg substitution"):
    val out = jvm(
      """@jvm("$0 + $1")
        |extern def concat(a: String, b: String): String
        |""".stripMargin
    )
    out should include ("def concat")
    out should include ("a + b")

  test("JvmGen: @jvm preserves return type annotation"):
    val out = jvm(
      """@jvm("System.currentTimeMillis()")
        |extern def nowMillis(): Long
        |""".stripMargin
    )
    out should include (": Long")
    out should include ("System.currentTimeMillis()")

  test("JvmGen: non-annotated extern def is skipped (no inline body)"):
    val out = jvm(
      """extern def doNativeThing(): Unit
        |""".stripMargin
    )
    out should not include ("def doNativeThing")

  test("JvmGen: @jvm and @js both present — JvmGen uses @jvm expression"):
    val out = jvm(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    out should include ("java.util.UUID.randomUUID().toString()")
    out should not include ("crypto.randomUUID()")

  // ─── JsGen — @js inline body ────────────────────────────────────────────

  test("JsGen: @js extern def emits JS function with inline body"):
    val out = js(
      """@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    out should include ("function randomUUID")
    out should include ("crypto.randomUUID()")

  test("JsGen: @js extern def with $0 arg substitution"):
    val out = js(
      """@js("(typeof require !== 'undefined' ? require('fs').existsSync($0) : false)")
        |extern def fileExists(path: String): Boolean
        |""".stripMargin
    )
    out should include ("function fileExists")
    out should include ("existsSync(path)")

  test("JsGen: @jvm-only extern def emits error-throwing JS stub"):
    val out = js(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    out should include ("function randomUUID")
    out should include ("throw new Error")
    out should include ("@jvm-only")

  test("JsGen: @jvm and @js both present — JsGen uses @js expression"):
    val out = js(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    out should include ("crypto.randomUUID()")
    out should not include ("@jvm-only")
    out should not include ("java.util.UUID")

  // ─── CapabilityCheck — JVM-only extern def in JS target ─────────────────

  private val spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)

  private def jsCaps: Capabilities = Capabilities(
    features      = Feature.values.toSet,
    outputs       = Set(OutputKind.JavaScriptSource),
    options       = Set.empty,
    spiRange      = spiRange,
    blockLanguages = Set.empty
  )

  private def normalised(code: String): NormalizedModule =
    Normalize(module(code))

  test("CapabilityCheck: @jvm-only extern def + JS target → JvmOnlyExternDef diagnostic"):
    val nm   = normalised(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    val diags = CapabilityCheck.validate(nm, jsCaps, "js")
    val jvmOnly = diags.collect { case Diagnostic.JvmOnlyExternDef(name, _) => name }
    jvmOnly should contain ("randomUUID")

  test("CapabilityCheck: @jvm + @js extern def + JS target → no JvmOnlyExternDef"):
    val nm = normalised(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |@js("crypto.randomUUID()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    val diags = CapabilityCheck.validate(nm, jsCaps, "js")
    val jvmOnly = diags.collect { case Diagnostic.JvmOnlyExternDef(name, _) => name }
    jvmOnly shouldBe empty

  test("CapabilityCheck: @jvm-only extern def + JVM target → no diagnostic"):
    val jvmCaps = Capabilities(
      features      = Feature.values.toSet,
      outputs       = Set(OutputKind.JvmBytecode),
      options       = Set.empty,
      spiRange      = spiRange,
      blockLanguages = Set.empty
    )
    val nm = normalised(
      """@jvm("java.util.UUID.randomUUID().toString()")
        |extern def randomUUID(): String
        |""".stripMargin
    )
    val diags = CapabilityCheck.validate(nm, jvmCaps, "jvm")
    val jvmOnly = diags.collect { case Diagnostic.JvmOnlyExternDef(name, _) => name }
    jvmOnly shouldBe empty
