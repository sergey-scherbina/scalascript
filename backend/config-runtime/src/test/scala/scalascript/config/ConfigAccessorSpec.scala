package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigAccessorSpec extends AnyFunSuite with Matchers:

  val root = ConfigValue.Map(Map(
    "server" -> ConfigValue.Map(Map(
      "port" -> ConfigValue.Num(8080),
      "host" -> ConfigValue.Str("localhost"),
      "tls"  -> ConfigValue.Bool(false),
    )),
    "app" -> ConfigValue.Str("myapp"),
  ))

  val acc = ConfigAccessor(root)

  test("getString") { acc.getString("app") shouldBe Some("myapp") }
  test("getInt")    { acc.getInt("server.port") shouldBe Some(8080) }
  test("getBool")   { acc.getBool("server.tls") shouldBe Some(false) }
  test("missing")   { acc.getString("does.not.exist") shouldBe None }

  test("section"):
    val srv = acc.section("server")
    srv.getInt("port") shouldBe Some(8080)
    srv.getString("host") shouldBe Some("localhost")

  test("requireString throws on missing"):
    an [ConfigError.MissingKey] should be thrownBy acc.requireString("missing")

  test("requireInt works"):
    acc.requireInt("server.port") shouldBe 8080

  test("selectDynamic chains"):
    val root2 = ConfigValue.Map(Map(
      "server" -> ConfigValue.Map(Map(
        "port" -> ConfigValue.Num(9000),
        "host" -> ConfigValue.Str("api.example.com"),
      ))
    ))
    val acc2 = ConfigAccessor(root2)
    val srv = acc2.selectDynamic("server")
    srv.getInt("port")    shouldBe Some(9000)
    srv.getString("host") shouldBe Some("api.example.com")

  test("selectDynamic on missing key returns empty accessor"):
    val acc2 = ConfigAccessor(ConfigValue.empty)
    val sub = acc2.selectDynamic("missing")
    sub.getString("anything") shouldBe None
