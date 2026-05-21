package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigLoaderSpec extends AnyFunSuite with Matchers:

  test("load from frontmatter only"):
    val loader = ConfigLoader(
      frontmatterYaml = "server:\n  port: 8080\n  host: localhost",
    )
    val result = loader.load()
    result.isRight shouldBe true
    val cfg = result.toOption.get
    cfg.get("server.port") shouldBe Some(ConfigValue.Num(8080))
    cfg.get("server.host") shouldBe Some(ConfigValue.Str("localhost"))

  test("fenced block overrides frontmatter"):
    val loader = ConfigLoader(
      frontmatterYaml = "port: 8080",
      fencedBlocks = List(FencedConfigBlock("", "port: 9090")),
    )
    val result = loader.load()
    result.isRight shouldBe true
    result.toOption.get.get("port") shouldBe Some(ConfigValue.Num(9090))

  test("named fenced block is scoped"):
    val loader = ConfigLoader(
      fencedBlocks = List(FencedConfigBlock("server", "port: 8080\nhost: localhost")),
    )
    val result = loader.load()
    result.isRight shouldBe true
    val cfg = result.toOption.get
    cfg.get("server.port") shouldBe Some(ConfigValue.Num(8080))
    cfg.get("port") shouldBe None

  test("env substitution in frontmatter"):
    val loader = ConfigLoader(
      frontmatterYaml = "port: ${env:TEST_CONFIG_PORT | 3000}",
      envLookup = Map("TEST_CONFIG_PORT" -> "7777").get,
    )
    val result = loader.load()
    result.isRight shouldBe true
    result.toOption.get.get("port") shouldBe Some(ConfigValue.Str("7777"))

  test("env substitution default when env absent"):
    val loader = ConfigLoader(
      frontmatterYaml = "port: ${env:ABSENT_VAR_XYZ | 3000}",
      envLookup = _ => None,
    )
    val result = loader.load()
    result.isRight shouldBe true
    result.toOption.get.get("port") shouldBe Some(ConfigValue.Str("3000"))
