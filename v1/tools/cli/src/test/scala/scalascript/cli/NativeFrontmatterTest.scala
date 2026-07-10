package scalascript.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import _root_.ssc.plugin.NativeDatabaseConfig

class NativeFrontmatterTest extends AnyFunSuite:
  private def source(text: String): java.io.File =
    val path = Files.createTempFile("ssc-native-frontmatter-", ".ssc")
    Files.writeString(path, text, StandardCharsets.UTF_8)
    path.toFile

  test("parse complete databases config after shebang"):
    val file = source(
      """#!/usr/bin/env ssc
        |---
        |databases:
        |  default:
        |    url: "jdbc:h2:mem:native"
        |    user: "${env:DB_USER}"
        |    password: ""
        |    driver: "org.h2.Driver"
        |---
        |```scalascript
        |println("ok")
        |```
        |""".stripMargin)

    val config = NativeFrontmatter.fromFiles(List(file))

    assert(config.databases == Map(
      "default" -> NativeDatabaseConfig(
        "jdbc:h2:mem:native",
        Some("${env:DB_USER}"),
        Some(""),
        Some("org.h2.Driver"))))

  test("identical duplicate database declarations across roots are accepted"):
    val yaml =
      """---
        |databases:
        |  default:
        |    url: "jdbc:h2:mem:same"
        |---
        |""".stripMargin

    val config = NativeFrontmatter.fromFiles(List(source(yaml), source(yaml)))

    assert(config.databases("default").url == "jdbc:h2:mem:same")

  test("conflicting database declarations fail before provider installation"):
    def yaml(url: String) =
      s"""---
         |databases:
         |  default:
         |    url: "$url"
         |---
         |""".stripMargin

    val error = intercept[IllegalArgumentException] {
      NativeFrontmatter.fromFiles(List(source(yaml("jdbc:h2:mem:a")), source(yaml("jdbc:h2:mem:b"))))
    }

    assert(error.getMessage.contains("conflicting native database 'default'"))

  test("database entries require a non-empty URL"):
    val file = source(
      """---
        |databases:
        |  default:
        |    user: "sa"
        |---
        |""".stripMargin)

    val error = intercept[IllegalArgumentException](NativeFrontmatter.fromFiles(List(file)))

    assert(error.getMessage.contains("requires a non-empty url"))
