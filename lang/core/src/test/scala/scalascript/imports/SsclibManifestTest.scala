package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

class SsclibManifestTest extends AnyFunSuite:

  test("parseString: minimal manifest with only required name"):
    val yaml = "name: io.example/my-lib\n"
    val m = SsclibManifest.parseString(yaml).get
    assert(m.name               == "io.example/my-lib")
    assert(m.version            == "0.1.0")
    assert(m.entry              == "src/main.ssc")
    assert(m.scalaScriptVersion == ">=1.60")
    assert(m.dependencies       == Nil)
    assert(m.description        == None)
    assert(m.author             == None)

  test("parseString: full manifest with all fields"):
    val yaml =
      """name: io.example/my-lib
        |version: 2.1.0
        |entry: src/lib.ssc
        |scala-script-version: ">=1.60"
        |description: A test library
        |author: Test Author
        |dependencies:
        |  - dep: io.scalascript/json:1.0.0
        |  - dep: io.example/utils:2.1.0
        |""".stripMargin
    val m = SsclibManifest.parseString(yaml).get
    assert(m.name               == "io.example/my-lib")
    assert(m.version            == "2.1.0")
    assert(m.entry              == "src/lib.ssc")
    assert(m.scalaScriptVersion == ">=1.60")
    assert(m.description        == Some("A test library"))
    assert(m.author             == Some("Test Author"))
    assert(m.dependencies       == List("io.scalascript/json:1.0.0", "io.example/utils:2.1.0"))

  test("parseString: dependencies as plain strings in list"):
    val yaml =
      """name: io.example/lib
        |dependencies:
        |  - io.example/dep1:1.0
        |  - io.example/dep2:2.0
        |""".stripMargin
    val m = SsclibManifest.parseString(yaml).get
    assert(m.dependencies == List("io.example/dep1:1.0", "io.example/dep2:2.0"))

  test("parseString: missing required field 'name' → Failure"):
    val result = SsclibManifest.parseString("version: 1.0.0\n")
    assert(result.isFailure)
    assert(result.failed.get.getMessage.contains("name"))

  test("parseString: empty document → Failure"):
    assert(SsclibManifest.parseString("").isFailure)

  test("parseString: null document → Failure"):
    assert(SsclibManifest.parseString("~").isFailure)

  test("parseString: version with explicit value"):
    val yaml =
      """name: io.example/lib
        |version: 1.0.0
        |""".stripMargin
    assert(SsclibManifest.parseString(yaml).get.version == "1.0.0")

  test("toYaml round-trip preserves all fields"):
    val original = SsclibManifest(
      name               = "io.example/my-lib",
      version            = "1.2.3",
      entry              = "src/api.ssc",
      scalaScriptVersion = ">=1.61",
      dependencies       = List("io.scalascript/json:1.0.0", "io.example/utils:2.0"),
      description        = Some("Round-trip test"),
      author             = Some("Alice"),
    )
    val yaml   = SsclibManifest.toYaml(original)
    val parsed = SsclibManifest.parseString(yaml).get
    assert(parsed.name               == original.name)
    assert(parsed.version            == original.version)
    assert(parsed.entry              == original.entry)
    assert(parsed.scalaScriptVersion == original.scalaScriptVersion)
    assert(parsed.dependencies       == original.dependencies)
    assert(parsed.description        == original.description)
    assert(parsed.author             == original.author)

  test("toYaml: omits dependencies section when list is empty"):
    val yaml = SsclibManifest.toYaml(SsclibManifest(name = "io.example/lib"))
    assert(!yaml.contains("dependencies"))

  test("toYaml: omits optional fields when absent"):
    val yaml = SsclibManifest.toYaml(SsclibManifest(name = "io.example/lib"))
    assert(!yaml.contains("description"))
    assert(!yaml.contains("author"))

  test("cacheId replaces slashes and colons"):
    assert(SsclibManifest(name = "io.example/my-lib").cacheId  == "io.example_my-lib")
    assert(SsclibManifest(name = "io.scalascript/json").cacheId == "io.scalascript_json")
