package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** std-yaml Phase 4 — yaml/yml fenced blocks bound to section.yaml in scope.
 *  Requires yaml-plugin on classpath (loaded via META-INF/services). */
class YamlFencedBlockTest extends AnyFunSuite:

  private def run(ssc: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(ssc))
    ps.flush()
    buf.toString.trim

  private def check(actual: String, expected: String): Unit =
    assert(actual == expected, s"\nActual:   '$actual'\nExpected: '$expected'")

  test("yaml block binds to section.yaml — yamlType returns YObj for mapping"):
    check(run(
      """|# Config
         |
         |```yaml
         |host: localhost
         |port: 8080
         |```
         |
         |# Test
         |
         |```scalascript
         |println(yamlType(Config.yaml))
         |```
         |""".stripMargin
    ), "YObj")

  test("yaml block — yamlGet retrieves field value"):
    check(run(
      """|# Settings
         |
         |```yaml
         |name: ScalaScript
         |```
         |
         |# Test
         |
         |```scalascript
         |println(yamlStr(yamlGet(Settings.yaml, "name")))
         |```
         |""".stripMargin
    ), "ScalaScript")

  test("yaml block — sequence yamlType is YArr, length correct"):
    check(run(
      """|# Data
         |
         |```yaml
         |- alpha
         |- beta
         |- gamma
         |```
         |
         |# Test
         |
         |```scalascript
         |println(yamlType(Data.yaml))
         |println(yamlArr(Data.yaml).length)
         |```
         |""".stripMargin
    ), "YArr\n3")

  test("yml alias also bound as section.yaml"):
    check(run(
      """|# Info
         |
         |```yml
         |status: ok
         |```
         |
         |# Test
         |
         |```scalascript
         |println(yamlType(Info.yaml))
         |```
         |""".stripMargin
    ), "YObj")

  test("yaml block — null value → YNull"):
    check(run(
      """|# Empty
         |
         |```yaml
         |null
         |```
         |
         |# Test
         |
         |```scalascript
         |println(yamlType(Empty.yaml))
         |```
         |""".stripMargin
    ), "YNull")

  test("yaml block — toYaml round-trip preserves keys"):
    check(run(
      """|# Cfg
         |
         |```yaml
         |host: localhost
         |port: 3000
         |```
         |
         |# Test
         |
         |```scalascript
         |val out = toYaml(Cfg.yaml)
         |println(out.contains("host"))
         |println(out.contains("localhost"))
         |```
         |""".stripMargin
    ), "true\ntrue")
