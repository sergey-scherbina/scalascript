package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigParserSpec extends AnyFunSuite with Matchers:

  test("parse YAML map"):
    val yaml = """
      |server:
      |  port: 8080
      |  host: "localhost"
      |""".stripMargin
    ConfigParser.parse(yaml) shouldBe Right(
      ConfigValue.Map(Map(
        "server" -> ConfigValue.Map(Map(
          "port" -> ConfigValue.Num(8080),
          "host" -> ConfigValue.Str("localhost"),
        ))
      ))
    )

  test("parse JSON"):
    val json = """{"key": "value", "num": 42}"""
    ConfigParser.parse(json, ConfigParser.Format.Json) shouldBe Right(
      ConfigValue.Map(Map(
        "key" -> ConfigValue.Str("value"),
        "num" -> ConfigValue.Num(42),
      ))
    )

  test("parse empty YAML"):
    ConfigParser.parseFrontmatter("") shouldBe Right(ConfigValue.empty)

  test("detectFormat"):
    ConfigParser.detectFormat("app.yaml")  shouldBe ConfigParser.Format.Yaml
    ConfigParser.detectFormat("app.yml")   shouldBe ConfigParser.Format.Yaml
    ConfigParser.detectFormat("app.json")  shouldBe ConfigParser.Format.Json
    ConfigParser.detectFormat("app.conf")  shouldBe ConfigParser.Format.Hocon
    ConfigParser.detectFormat("app.hocon") shouldBe ConfigParser.Format.Hocon
    ConfigParser.detectFormat("unknown")   shouldBe ConfigParser.Format.Yaml
