package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigValueSpec extends AnyFunSuite with Matchers:

  test("from: string"):
    ConfigValue.from("hello") shouldBe ConfigValue.Str("hello")

  test("from: integer"):
    ConfigValue.from(42) shouldBe ConfigValue.Num(42.0)

  test("from: boolean"):
    ConfigValue.from(true) shouldBe ConfigValue.Bool(true)

  test("from: null"):
    ConfigValue.from(null) shouldBe ConfigValue.Null

  test("get: dotted path"):
    val cv = ConfigValue.Map(Map(
      "server" -> ConfigValue.Map(Map("port" -> ConfigValue.Num(8080)))
    ))
    cv.get("server.port") shouldBe Some(ConfigValue.Num(8080))
    cv.get("server.host") shouldBe None
    cv.get("missing")     shouldBe None

  test("deepMerge: map overlay"):
    val a = ConfigValue.Map(Map("x" -> ConfigValue.Str("a"), "y" -> ConfigValue.Str("y")))
    val b = ConfigValue.Map(Map("x" -> ConfigValue.Str("b"), "z" -> ConfigValue.Str("z")))
    a.deepMerge(b) shouldBe ConfigValue.Map(Map(
      "x" -> ConfigValue.Str("b"),
      "y" -> ConfigValue.Str("y"),
      "z" -> ConfigValue.Str("z"),
    ))

  test("deepMerge: non-map wins"):
    ConfigValue.Str("a").deepMerge(ConfigValue.Str("b")) shouldBe ConfigValue.Str("b")

  test("set: nested path creates intermediate maps"):
    val cv = ConfigValue.empty.set("server.port", ConfigValue.Num(8080))
    cv.get("server.port") shouldBe Some(ConfigValue.Num(8080))
