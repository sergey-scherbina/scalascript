package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class SubstitutionEngineSpec extends AnyFunSuite with Matchers:

  val env: String => Option[String] = Map(
    "PORT" -> "9090",
    "HOST" -> "example.com",
  ).get

  test("resolve ${env:VAR}"):
    SubstitutionEngine.resolveString("${env:PORT}", envLookup = env) shouldBe Right("9090")

  test("resolve ${env:VAR | default} — env present"):
    SubstitutionEngine.resolveString("${env:PORT | 8080}", envLookup = env) shouldBe Right("9090")

  test("resolve ${env:VAR | default} — env absent"):
    SubstitutionEngine.resolveString("${env:MISSING | 8080}", envLookup = env) shouldBe Right("8080")

  test("error on missing ${env:VAR} without default"):
    SubstitutionEngine.resolveString("${env:MISSING}", envLookup = env).isLeft shouldBe true

  test("resolve HOCON ${?VAR} — optional, absent = empty string"):
    SubstitutionEngine.resolveString("${?MISSING}", envLookup = env) shouldBe Right("")

  test("resolve HOCON ${VAR} — present"):
    SubstitutionEngine.resolveString("${HOST}", envLookup = env) shouldBe Right("example.com")

  test("resolve multiple substitutions in one string"):
    SubstitutionEngine.resolveString(
      "http://${env:HOST}:${env:PORT}/path",
      envLookup = env
    ) shouldBe Right("http://example.com:9090/path")

  test("resolveTree: recurses into maps"):
    val tree = ConfigValue.Map(Map(
      "url" -> ConfigValue.Str("http://${env:HOST}:${env:PORT}"),
      "port" -> ConfigValue.Num(8080),
    ))
    val result = SubstitutionEngine.resolveTree(tree, envLookup = env)
    result shouldBe Right(ConfigValue.Map(Map(
      "url"  -> ConfigValue.Str("http://example.com:9090"),
      "port" -> ConfigValue.Num(8080),
    )))
