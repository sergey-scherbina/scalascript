package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ScalaConfigEmitterSpec extends AnyFunSuite with Matchers:

  val cfg = ConfigValue.Map(Map(
    "port"  -> ConfigValue.Num(8080),
    "host"  -> ConfigValue.Str("localhost"),
    "debug" -> ConfigValue.Bool(false),
  ))

  test("emitPreamble Embedded contains __ssc_config"):
    val code = ScalaConfigEmitter.emitPreamble(cfg, ScalaConfigEmitter.Strategy.Embedded)
    code should include ("__ssc_config")
    code should include (""""port" -> 8080""")
    code should include (""""host" -> "localhost"""")

  test("emitPreamble CompanionObject emits object AppConfig"):
    val code = ScalaConfigEmitter.emitPreamble(cfg, ScalaConfigEmitter.Strategy.CompanionObject)
    code should include ("object AppConfig")
    code should include ("val port")
    code should include ("8080")

  test("emitPreamble ApplicationConf is empty (config goes to file)"):
    ScalaConfigEmitter.emitPreamble(cfg, ScalaConfigEmitter.Strategy.ApplicationConf) shouldBe ""

  test("toHocon: scalar values"):
    ScalaConfigEmitter.toHocon(ConfigValue.Str("hello"), "") shouldBe """"hello""""
    ScalaConfigEmitter.toHocon(ConfigValue.Num(42), "")     shouldBe "42"
    ScalaConfigEmitter.toHocon(ConfigValue.Bool(true), "")  shouldBe "true"

  test("toHocon: nested map"):
    val nested = ConfigValue.Map(Map("key" -> ConfigValue.Str("val")))
    val hocon  = ScalaConfigEmitter.toHocon(nested, "")
    hocon should include ("key = ")
    hocon should include (""""val"""")

  test("Strategy.fromString"):
    ScalaConfigEmitter.Strategy.fromString("application.conf") shouldBe ScalaConfigEmitter.Strategy.ApplicationConf
    ScalaConfigEmitter.Strategy.fromString("object")           shouldBe ScalaConfigEmitter.Strategy.CompanionObject
    ScalaConfigEmitter.Strategy.fromString("embedded")         shouldBe ScalaConfigEmitter.Strategy.Embedded
    ScalaConfigEmitter.Strategy.fromString("unknown")          shouldBe ScalaConfigEmitter.Strategy.Embedded

  test("Strategy.fromConfigValue"):
    val cv = ConfigValue.Map(Map(
      "config" -> ConfigValue.Map(Map("scala-output" -> ConfigValue.Str("object")))
    ))
    ScalaConfigEmitter.Strategy.fromConfigValue(cv) shouldBe ScalaConfigEmitter.Strategy.CompanionObject

  test("writeApplicationConf creates file"):
    val dir = java.nio.file.Files.createTempDirectory("ssc-scala-cfg")
    try
      ScalaConfigEmitter.writeApplicationConf(cfg, dir)
      val content = java.nio.file.Files.readString(dir.resolve("application.conf"))
      content should include ("port = 8080")
      content should include (""""localhost"""")
    finally
      dir.toFile.listFiles().foreach(_.delete())
      dir.toFile.delete()
