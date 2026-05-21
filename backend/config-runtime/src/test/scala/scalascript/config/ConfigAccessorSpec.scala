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
