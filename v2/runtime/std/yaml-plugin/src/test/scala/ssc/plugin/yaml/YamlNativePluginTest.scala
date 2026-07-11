package ssc.plugin.yaml

import org.scalatest.funsuite.AnyFunSuite
import ssc.{V2PluginRegistry, Value}
import ssc.plugin.NativePluginHost

class YamlNativePluginTest extends AnyFunSuite:
  private def call(name: String, args: Value*): Value =
    V2PluginRegistry.lookupGlobal(name).get match
      case closure: Value.ClosV =>
        val env = ssc.Runtime.extend(closure.env, args.toArray)
        ssc.Runtime.run(closure.code, env)
      case _ => fail(s"$name is not callable")

  private def install(): Unit =
    NativePluginHost.installProviders(List(YamlNativePlugin()))

  test("parse and navigate nested portable values"):
    install()
    val root = call("parseYaml", Value.StrV(
      "server:\n  port: 8080\n  debug: true\ntags: [web, api]\n" +
        "flow: {answer: 42, enabled: false}\nquoted: \"color #fff\" # comment\n" +
        "literal: |\n  line one\n  line two\nfolded: >\n  fold one\n  fold two\n"))
    assert(call("yamlType", root) == Value.StrV("YObj"))
    val server = call("yamlGet", root, Value.StrV("server"))
    assert(call("yamlNum", call("yamlGet", server, Value.StrV("port"))) == Value.FloatV(8080.0))
    assert(call("yamlBool", call("yamlGet", server, Value.StrV("debug"))) == Value.BoolV(true))
    val flow = call("yamlGet", root, Value.StrV("flow"))
    assert(call("yamlNum", call("yamlGet", flow, Value.StrV("answer"))) == Value.FloatV(42.0))
    assert(call("yamlStr", call("yamlGet", root, Value.StrV("quoted"))) == Value.StrV("color #fff"))
    assert(call("yamlStr", call("yamlGet", root, Value.StrV("literal"))) == Value.StrV("line one\nline two"))
    assert(call("yamlStr", call("yamlGet", root, Value.StrV("folded"))) == Value.StrV("fold one\nfold two"))
    assert(call("yamlType", call("yamlGet", root, Value.StrV("missing"))) == Value.StrV("YNull"))

  test("serialization is sorted and round-trips"):
    install()
    val root = call("parseYaml", Value.StrV("z: 2\na: hello\nitems: [one, two]\n"))
    val rendered = call("toYaml", root)
    assert(rendered == Value.StrV("a: hello\nitems:\n  - one\n  - two\nz: 2\n"))
    assert(call("toYaml", call("parseYaml", rendered)) == rendered)

    val complete = call("parseYaml", Value.StrV(
      "nil: null\nflag: true\nnumber: 3.5\ntext: '42'\n" +
        "multiline: |\n  first line\n  second line\n" +
        "nested:\n  values:\n    - one\n    - two\narrays:\n  -\n    - 1\n    - 2\n" +
        "objects:\n  -\n    b: 2\n    a: one\n"))
    val completeYaml = call("toYaml", complete)
    val reparsed = call("parseYaml", completeYaml)
    assert(call("toYaml", reparsed) == completeYaml)
    assert(call("yamlType", call("yamlGet", reparsed, Value.StrV("text"))) == Value.StrV("YStr"))
    assert(call("yamlType", call("yamlGet", reparsed, Value.StrV("arrays"))) == Value.StrV("YArr"))

    val rootArray = call("parseYaml", Value.StrV("- one\n-\n  nested: true\n"))
    val rootArrayYaml = call("toYaml", rootArray)
    assert(call("toYaml", call("parseYaml", rootArrayYaml)) == rootArrayYaml)

  test("malformed input is bounded and accessor defaults are total"):
    install()
    val error = intercept[IllegalArgumentException](call("parseYaml", Value.StrV("x: bad: value")))
    assert(error.getMessage.startsWith("YAML parse error:"))
    assert(call("yamlType", Value.IntV(1)) == Value.StrV("unknown"))
    assert(call("yamlStr", Value.IntV(1)) == Value.UnitV)
    assert(call("yamlNum", Value.IntV(1)) == Value.FloatV(0.0))
    assert(call("yamlBool", Value.IntV(1)) == Value.BoolV(false))
    assert(call("yamlArr", Value.IntV(1)) == Value.DataV("Nil", Vector.empty))
    assert(call("yamlGet", Value.IntV(1), Value.StrV("missing")) == Value.DataV("YNull", Vector.empty))
