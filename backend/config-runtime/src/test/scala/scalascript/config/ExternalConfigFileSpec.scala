package scalascript.config

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}

class ExternalConfigFileSpec extends AnyFunSuite with Matchers:

  def withTempDir[A](body: Path => A): A =
    val dir = Files.createTempDirectory("ssc-config-test")
    try body(dir)
    finally
      dir.toFile.listFiles().foreach(_.delete())
      dir.toFile.delete()

  test("load external YAML file"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("app.yaml"), "port: 9999\nhost: test")
      val loader = ConfigLoader(
        externalFiles = List(ExternalConfigFile("app.yaml", basePath = dir)),
        envLookup     = _ => None,
      )
      val result = loader.load()
      result.isRight shouldBe true
      result.toOption.get.get("port") shouldBe Some(ConfigValue.Num(9999))
    }

  test("optional missing file silently ignored"):
    withTempDir { dir =>
      val loader = ConfigLoader(
        externalFiles = List(ExternalConfigFile("missing.yaml", optional = true, basePath = dir)),
        envLookup     = _ => None,
      )
      loader.load().isRight shouldBe true
    }

  test("required missing file returns error"):
    withTempDir { dir =>
      val loader = ConfigLoader(
        externalFiles = List(ExternalConfigFile("missing.yaml", optional = false, basePath = dir)),
        envLookup     = _ => None,
      )
      loader.load().isLeft shouldBe true
    }

  test("later file overrides earlier"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("base.yaml"),     "x: first\ny: base")
      Files.writeString(dir.resolve("override.yaml"), "x: second\nz: extra")
      val loader = ConfigLoader(
        externalFiles = List(
          ExternalConfigFile("base.yaml",     basePath = dir),
          ExternalConfigFile("override.yaml", basePath = dir),
        ),
        envLookup = _ => None,
      )
      val cfg = loader.load().toOption.get
      cfg.get("x") shouldBe Some(ConfigValue.Str("second"))
      cfg.get("y") shouldBe Some(ConfigValue.Str("base"))
      cfg.get("z") shouldBe Some(ConfigValue.Str("extra"))
    }

  test("file beats frontmatter (default priority)"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("app.yaml"), "port: 9999")
      val loader = ConfigLoader(
        frontmatterYaml = "port: 8080",
        externalFiles   = List(ExternalConfigFile("app.yaml", basePath = dir)),
        envLookup       = _ => None,
      )
      val cfg = loader.load().toOption.get
      cfg.get("port") shouldBe Some(ConfigValue.Num(9999))
    }

  test("fromFrontmatter extracts files list"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("app.yaml"), "extra: value")
      val fm     = "config:\n  files:\n    - app.yaml"
      val loader = ConfigLoader.fromFrontmatter(fm, basePath = dir, envLookup = _ => None)
      val cfg    = loader.load().toOption.get
      cfg.get("extra") shouldBe Some(ConfigValue.Str("value"))
    }

  test("fromFrontmatter shorthand list"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("app.yaml"), "shorthand: true")
      val fm     = "config:\n  - app.yaml"
      val loader = ConfigLoader.fromFrontmatter(fm, basePath = dir, envLookup = _ => None)
      val cfg    = loader.load().toOption.get
      cfg.get("shorthand") shouldBe Some(ConfigValue.Bool(true))
    }

  test("HOCON include resolves relative file"):
    withTempDir { dir =>
      Files.writeString(dir.resolve("base.conf"),  "base_key: base_val")
      Files.writeString(dir.resolve("app.hocon"), "include \"base.conf\"\napp_key: app_val")
      val loader = ConfigLoader(
        externalFiles = List(ExternalConfigFile("app.hocon", basePath = dir)),
        envLookup     = _ => None,
      )
      val cfg = loader.load().toOption.get
      cfg.get("app_key")  shouldBe Some(ConfigValue.Str("app_val"))
      cfg.get("base_key") shouldBe Some(ConfigValue.Str("base_val"))
    }
