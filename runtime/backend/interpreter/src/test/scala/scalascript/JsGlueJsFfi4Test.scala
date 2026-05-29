package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.imports.GlueJsPreambleRegistry

/** arch-ffi-p4 — JsGen.generateRuntime injects js/glue.js preambles. */
class JsGlueJsFfi4Test extends AnyFunSuite:

  test("JsGen.generateRuntime: no glue preamble by default") {
    GlueJsPreambleRegistry.clear()
    val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
    assert(!rt.contains("glue preambles"), s"unexpected glue header in runtime without registration")
  }

  test("JsGen.generateRuntime: registered glue preamble appears before runtime helpers") {
    GlueJsPreambleRegistry.clear()
    val glueJs = "function _glueFn() { return 42; }"
    GlueJsPreambleRegistry.addPreamble("dep:io.example/test:1.0.0", glueJs)
    try
      val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
      assert(rt.contains("_glueFn"), s"glue function should appear in generateRuntime output")
      assert(rt.contains("glue preambles"), s"glue preamble section header should be present")
      // Glue content must appear before the regular runtime (scalascript JS runtime header is first).
      val glueIdx = rt.indexOf("_glueFn")
      val rtIdx   = rt.indexOf("_show")    // _show is a core runtime helper
      assert(glueIdx < rtIdx, s"glue preamble (idx=$glueIdx) should precede runtime helpers (idx=$rtIdx)")
    finally
      GlueJsPreambleRegistry.clear()
  }

  test("JsGen.generateRuntime: multiple glue preambles all included") {
    GlueJsPreambleRegistry.clear()
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib-a:1.0.0", "function _glueA() {}")
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib-b:2.0.0", "function _glueB() {}")
    try
      val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
      assert(rt.contains("_glueA"), s"_glueA should appear in generateRuntime output")
      assert(rt.contains("_glueB"), s"_glueB should appear in generateRuntime output")
    finally
      GlueJsPreambleRegistry.clear()
  }

  test("JsGen.generateRuntime: glue preamble present with newline termination") {
    GlueJsPreambleRegistry.clear()
    GlueJsPreambleRegistry.addPreamble("dep:io.example/lib:1.0.0", "const _x = 1;")
    try
      val rt = JsGen.generateRuntime(Set(JsGen.Capability.Core))
      // The newline after the preamble must be present so subsequent code is not on the same line.
      val glueEnd = rt.indexOf("const _x = 1;") + "const _x = 1;".length
      assert(rt.charAt(glueEnd) == '\n', s"glue content should be followed by newline; got '${rt.charAt(glueEnd)}'")
    finally
      GlueJsPreambleRegistry.clear()
  }
