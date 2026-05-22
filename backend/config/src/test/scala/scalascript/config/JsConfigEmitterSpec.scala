package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class JsConfigEmitterSpec extends AnyFunSuite with Matchers:

  val cfg = ConfigValue.Map(Map(
    "server" -> ConfigValue.Map(Map(
      "port" -> ConfigValue.Num(8080),
      "host" -> ConfigValue.Str("localhost"),
    )),
    "debug" -> ConfigValue.Bool(false),
  ))

  test("toJson: map"):
    val json = JsConfigEmitter.toJson(cfg)
    json should include (""""port": 8080""")
    json should include (""""host": "localhost"""")
    json should include (""""debug": false""")

  test("toJson: escapes quotes in strings"):
    JsConfigEmitter.toJson(ConfigValue.Str("""say "hi"""")) shouldBe "\"say \\\"hi\\\"\""

  test("emit Bake strategy contains __ssc_config"):
    val js = JsConfigEmitter.emit(cfg, JsConfigEmitter.Strategy.Bake)
    js should include ("const __ssc_config =")
    js should include ("__ssc_cfg")

  test("emit ProcessEnv strategy uses process.env"):
    val js = JsConfigEmitter.emit(cfg, JsConfigEmitter.Strategy.ProcessEnv)
    js should include ("process.env")
    js should include ("__ssc_config")

  test("emit Runtime strategy uses window.__SSC_CONFIG"):
    val js = JsConfigEmitter.emit(cfg, JsConfigEmitter.Strategy.Runtime)
    js should include ("window.__SSC_CONFIG")
    js should include ("config.json")

  test("Strategy.fromString"):
    JsConfigEmitter.Strategy.fromString("process-env") shouldBe JsConfigEmitter.Strategy.ProcessEnv
    JsConfigEmitter.Strategy.fromString("runtime")     shouldBe JsConfigEmitter.Strategy.Runtime
    JsConfigEmitter.Strategy.fromString("bake")        shouldBe JsConfigEmitter.Strategy.Bake
    JsConfigEmitter.Strategy.fromString("unknown")     shouldBe JsConfigEmitter.Strategy.Bake

  test("Strategy.fromConfigValue: reads js-binding key"):
    val cv = ConfigValue.Map(Map(
      "config" -> ConfigValue.Map(Map("js-binding" -> ConfigValue.Str("process-env")))
    ))
    JsConfigEmitter.Strategy.fromConfigValue(cv) shouldBe JsConfigEmitter.Strategy.ProcessEnv

  test("flattenToEnvKeys"):
    val flat = JsConfigEmitter.flattenToEnvKeys(cfg, "").toMap
    flat("server.port") shouldBe ConfigValue.Num(8080)
    flat("server.host") shouldBe ConfigValue.Str("localhost")
    flat("debug")       shouldBe ConfigValue.Bool(false)
