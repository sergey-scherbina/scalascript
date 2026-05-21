package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ConfigDecoderSpec extends AnyFunSuite with Matchers:

  // ── Primitives ──────────────────────────────────────────────────────────

  test("decode String"):
    ConfigDecoder[String].decode(ConfigValue.Str("hello")) shouldBe Right("hello")

  test("decode Int from Num"):
    ConfigDecoder[Int].decode(ConfigValue.Num(42)) shouldBe Right(42)

  test("decode Int from Str"):
    ConfigDecoder[Int].decode(ConfigValue.Str("42")) shouldBe Right(42)

  test("decode Boolean"):
    ConfigDecoder[Boolean].decode(ConfigValue.Bool(true)) shouldBe Right(true)

  test("decode Double"):
    ConfigDecoder[Double].decode(ConfigValue.Num(3.14)) shouldBe Right(3.14)

  test("decode Option[Int] — present"):
    ConfigDecoder[Option[Int]].decode(ConfigValue.Num(5)) shouldBe Right(Some(5))

  test("decode Option[Int] — null"):
    ConfigDecoder[Option[Int]].decode(ConfigValue.Null) shouldBe Right(None)

  test("decode List[String]"):
    val cv = ConfigValue.Lst(List(ConfigValue.Str("a"), ConfigValue.Str("b")))
    ConfigDecoder[List[String]].decode(cv) shouldBe Right(List("a", "b"))

  // ── Case class derivation ───────────────────────────────────────────────

  case class Srv(port: Int, host: String) derives Config

  test("derives Config — decode case class"):
    val cv = ConfigValue.Map(Map(
      "port" -> ConfigValue.Num(8080),
      "host" -> ConfigValue.Str("localhost"),
    ))
    ConfigDecoder[Srv].decode(cv) shouldBe Right(Srv(8080, "localhost"))

  test("derives Config — missing optional field is Null → decode as Option None"):
    case class WithOpt(port: Int, label: Option[String]) derives Config
    val cv = ConfigValue.Map(Map("port" -> ConfigValue.Num(9090)))
    ConfigDecoder[WithOpt].decode(cv) shouldBe Right(WithOpt(9090, None))

  test("derives Config — nested case class"):
    case class Tls(cert: String, key: String) derives Config
    case class App(port: Int, tls: Tls) derives Config
    val cv = ConfigValue.Map(Map(
      "port" -> ConfigValue.Num(443),
      "tls"  -> ConfigValue.Map(Map(
        "cert" -> ConfigValue.Str("cert.pem"),
        "key"  -> ConfigValue.Str("key.pem"),
      )),
    ))
    ConfigDecoder[App].decode(cv) shouldBe Right(App(443, Tls("cert.pem", "key.pem")))

  // ── ConfigAccessor[T] ───────────────────────────────────────────────────

  test("ConfigAccessor.apply[T] decodes section"):
    val root = ConfigValue.Map(Map(
      "server" -> ConfigValue.Map(Map(
        "port" -> ConfigValue.Num(7777),
        "host" -> ConfigValue.Str("example.com"),
      ))
    ))
    val acc = ConfigAccessor(root)
    acc[Srv]("server") shouldBe Right(Srv(7777, "example.com"))

  test("ConfigAccessor.as[T] decodes root"):
    case class Root(port: Int, host: String) derives Config
    val root = ConfigValue.Map(Map(
      "port" -> ConfigValue.Num(8080),
      "host" -> ConfigValue.Str("localhost"),
    ))
    ConfigAccessor(root).as[Root] shouldBe Right(Root(8080, "localhost"))

  test("ConfigAccessor.apply[T] missing section"):
    ConfigAccessor(ConfigValue.empty)[Srv]("missing").isLeft shouldBe true
