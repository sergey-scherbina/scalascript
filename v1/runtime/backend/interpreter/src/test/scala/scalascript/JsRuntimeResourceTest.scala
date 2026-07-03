package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsRuntimeOptics, JsRuntimeResource}

/** The JS runtime fragments are now real `.mjs` resources loaded by [[JsRuntimeResource]]
 *  (`specs/js-runtime-resources.md`). These tests pin the loader contract and — crucially —
 *  the byte-shape `JsGen` depends on (leading/trailing newline), plus an `assume(node)` syntax
 *  check on the raw `.mjs` (the concrete win of moving JS out of Scala string constants). */
class JsRuntimeResourceTest extends AnyFunSuite with Matchers:

  test("JsRuntimeOptics is the verbatim optics.mjs resource"):
    JsRuntimeOptics shouldBe JsRuntimeResource.load("optics.mjs")
    JsRuntimeOptics should include ("function _makeLens(")
    JsRuntimeOptics should include ("function _makeOptional(")
    JsRuntimeOptics should include ("function _makeTraversal(")
    JsRuntimeOptics should include ("function _makePrism(")

  test("the loaded fragment preserves the leading + trailing newline JsGen relies on"):
    JsRuntimeOptics.startsWith("\n") shouldBe true
    JsRuntimeOptics.endsWith("\n") shouldBe true

  test("the loader caches — repeat load returns the same instance"):
    (JsRuntimeResource.load("optics.mjs") eq JsRuntimeResource.load("optics.mjs")) shouldBe true

  test("a missing resource fails loudly"):
    an[IllegalStateException] should be thrownBy JsRuntimeResource.load("does-not-exist.mjs")

  test("Node syntax-checks the raw optics.mjs resource (assume node)"):
    assume(nodeAvailable, "node not on PATH — skipping node --check")
    val f = java.io.File.createTempFile("optics", ".mjs")
    try
      java.nio.file.Files.writeString(f.toPath, JsRuntimeOptics)
      val code = scala.sys.process.Process(Seq("node", "--check", f.getAbsolutePath))
        .!(scala.sys.process.ProcessLogger(_ => (), _ => ()))
      code shouldBe 0
    finally f.delete()

  private def nodeAvailable: Boolean =
    try scala.sys.process.Process(Seq("node", "--version")).!(scala.sys.process.ProcessLogger(_ => (), _ => ())) == 0
    catch case _: Throwable => false
