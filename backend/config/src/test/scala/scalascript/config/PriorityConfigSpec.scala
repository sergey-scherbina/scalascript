package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PriorityConfigSpec extends AnyFunSuite with Matchers:

  test("fromConfigValue: standard override"):
    val cv = ConfigValue.Map(Map(
      "config" -> ConfigValue.Map(Map(
        "priority" -> ConfigValue.Lst(List(
          ConfigValue.Str("frontmatter"),
          ConfigValue.Str("files"),
          ConfigValue.Str("blocks"),
        ))
      ))
    ))
    PriorityConfig.fromConfigValue(cv) shouldBe
      Some(List(Priority.Frontmatter, Priority.Files, Priority.Blocks))

  test("fromConfigValue: absent → None"):
    PriorityConfig.fromConfigValue(ConfigValue.empty) shouldBe None

  test("fromConfigValue: unknown strings filtered"):
    val cv = ConfigValue.Map(Map(
      "config" -> ConfigValue.Map(Map(
        "priority" -> ConfigValue.Lst(List(ConfigValue.Str("bogus")))
      ))
    ))
    PriorityConfig.fromConfigValue(cv) shouldBe None

  test("ConfigLoader.withPriority — frontmatter wins when it's highest"):
    val fm    = "port: 8080"
    val block = FencedConfigBlock("", "port: 9090")
    // Custom order: Frontmatter > Blocks
    val loader = ConfigLoader(
      frontmatterYaml = fm,
      fencedBlocks    = List(block),
      envLookup       = _ => None,
    ).withPriority(List(Priority.Frontmatter, Priority.Blocks))
    val result = loader.load().toOption.get
    result.get("port") shouldBe Some(ConfigValue.Num(8080))   // frontmatter wins

  test("fromFrontmatter auto-detects priority override"):
    val fm =
      """
        |port: 8080
        |config:
        |  priority: [frontmatter, files, blocks]
        |""".stripMargin
    val block  = FencedConfigBlock("", "port: 9090")
    val loader = ConfigLoader.fromFrontmatter(fm, fencedBlocks = List(block), envLookup = _ => None)
    val result = loader.load().toOption.get
    // Frontmatter is highest priority → 8080 wins over block's 9090
    result.get("port") shouldBe Some(ConfigValue.Num(8080))
