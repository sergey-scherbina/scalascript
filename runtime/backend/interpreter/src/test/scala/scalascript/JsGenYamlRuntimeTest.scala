package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen

/** std-yaml Phase 3 — JS preamble includes parseYaml/toYaml/yamlType/etc. */
class JsGenYamlRuntimeTest extends AnyFunSuite:

  private def preamble: String = JsGen.generateRuntime(JsGen.Capability.all)

  test("preamble defines parseYaml"):
    assert(preamble.contains("function parseYaml("))

  test("preamble defines toYaml"):
    assert(preamble.contains("function toYaml("))

  test("preamble defines yamlType"):
    assert(preamble.contains("function yamlType("))

  test("preamble defines yamlStr"):
    assert(preamble.contains("function yamlStr("))

  test("preamble defines yamlNum"):
    assert(preamble.contains("function yamlNum("))

  test("preamble defines yamlBool"):
    assert(preamble.contains("function yamlBool("))

  test("preamble defines yamlArr"):
    assert(preamble.contains("function yamlArr("))

  test("preamble defines yamlGet"):
    assert(preamble.contains("function yamlGet("))

  test("preamble defines _YStr constructor"):
    assert(preamble.contains("function _YStr("))

  test("preamble defines _YObj constructor"):
    assert(preamble.contains("function _YObj("))

  test("preamble defines _YArr constructor"):
    assert(preamble.contains("function _YArr("))

  test("preamble defines _yamlParse"):
    assert(preamble.contains("function _yamlParse("))

  test("preamble defines _toYamlVal serializer"):
    assert(preamble.contains("function _toYamlVal("))
