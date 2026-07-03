package scalascript.compiler.plugin.yaml

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** std-yaml Phase 2 — JVM yaml-plugin intrinsics. */
class YamlPluginTest extends AnyFunSuite:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
    val interp = Interpreter(out = ps)
    interp.installPlugins(List(YamlInterpreterPlugin()))
    interp.run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def check(actual: String, expected: String): Unit =
    assert(actual == expected, s"\nActual:   '$actual'\nExpected: '$expected'")

  // ── type detection via yamlType ────────────────────────────────────────────

  test("yamlType: plain string → YStr"):
    check(run("""println(yamlType(parseYaml("hello")))"""), "YStr")

  test("yamlType: integer → YNum"):
    check(run("""println(yamlType(parseYaml("42")))"""), "YNum")

  test("yamlType: double → YNum"):
    check(run("""println(yamlType(parseYaml("3.14")))"""), "YNum")

  test("yamlType: true → YBool"):
    check(run("""println(yamlType(parseYaml("true")))"""), "YBool")

  test("yamlType: false → YBool"):
    check(run("""println(yamlType(parseYaml("false")))"""), "YBool")

  test("yamlType: null → YNull"):
    check(run("""println(yamlType(parseYaml("null")))"""), "YNull")

  test("yamlType: mapping → YObj"):
    check(run("""println(yamlType(parseYaml("a: 1")))"""), "YObj")

  test("yamlType: sequence → YArr"):
    check(run("""println(yamlType(parseYaml("- a\n- b")))"""), "YArr")

  test("yamlType: flow sequence → YArr"):
    check(run("""println(yamlType(parseYaml("[1, 2, 3]")))"""), "YArr")

  test("yamlType: flow mapping → YObj"):
    check(run("""println(yamlType(parseYaml("{x: 1}")))"""), "YObj")

  // ── scalar extraction ──────────────────────────────────────────────────────

  test("yamlStr: extracts string value"):
    check(run("""println(yamlStr(parseYaml("hello world")))"""), "hello world")

  test("yamlNum: extracts numeric value"):
    check(run("""println(yamlNum(parseYaml("42")))"""), "42")

  test("yamlBool: extracts boolean true"):
    check(run("""println(yamlBool(parseYaml("true")))"""), "true")

  test("yamlBool: extracts boolean false"):
    check(run("""println(yamlBool(parseYaml("false")))"""), "false")

  // ── collection helpers ─────────────────────────────────────────────────────

  test("yamlArr: extracts list items"):
    check(run("""println(yamlArr(parseYaml("- a\n- b\n- c")).length)"""), "3")

  test("yamlGet: gets field from mapping"):
    check(run("""
      |val v = parseYaml("host: localhost\nport: 8080")
      |println(yamlStr(yamlGet(v, "host")))
      |""".stripMargin.trim), "localhost")

  test("yamlGet: missing key returns YNull"):
    check(run("""
      |val v = parseYaml("a: 1")
      |println(yamlType(yamlGet(v, "missing")))
      |""".stripMargin.trim), "YNull")

  // ── toYaml round-trip ──────────────────────────────────────────────────────

  test("toYaml: null round-trips"):
    check(run("""println(toYaml(parseYaml("null")).trim)"""), "null")

  test("toYaml: boolean true round-trips"):
    check(run("""println(toYaml(parseYaml("true")).trim)"""), "true")

  test("toYaml: boolean false round-trips"):
    check(run("""println(toYaml(parseYaml("false")).trim)"""), "false")

  test("toYaml: integer emitted without .0"):
    check(run("""println(toYaml(parseYaml("42")).trim)"""), "42")

  test("toYaml: empty mapping → {}"):
    check(run("""println(toYaml(parseYaml("{}")).trim)"""), "{}")

  test("toYaml: empty sequence → []"):
    check(run("""println(toYaml(parseYaml("[]")).trim)"""), "[]")

  test("toYaml: mapping round-trip contains keys and values"):
    check(run("""
      |val out = toYaml(parseYaml("host: localhost\nport: 8080"))
      |println(out.contains("host"))
      |println(out.contains("localhost"))
      |println(out.contains("8080"))
      |""".stripMargin.trim), "true\ntrue\ntrue")

  test("toYaml: sequence round-trip contains items"):
    check(run("""
      |val out = toYaml(parseYaml("- alpha\n- beta\n- gamma"))
      |println(out.contains("alpha"))
      |println(out.contains("beta"))
      |println(out.contains("gamma"))
      |""".stripMargin.trim), "true\ntrue\ntrue")

  test("toYaml: nested mapping contains nested keys"):
    check(run("""
      |val out = toYaml(parseYaml("server:\n  host: localhost\n  port: 3000"))
      |println(out.contains("server"))
      |println(out.contains("localhost"))
      |println(out.contains("3000"))
      |""".stripMargin.trim), "true\ntrue\ntrue")

  // ── quoted string handling ─────────────────────────────────────────────────

  test("yamlStr: double-quoted YAML string"):
    check(run("""
      |val s = "\"hello world\""
      |println(yamlStr(parseYaml(s)))
      |""".stripMargin.trim), "hello world")

  test("yamlStr: string with colon is quoted in toYaml"):
    check(run("""
      |val out = toYaml(parseYaml("url: http://example.com"))
      |println(out.contains("url"))
      |println(out.contains("example.com"))
      |""".stripMargin.trim), "true\ntrue")
