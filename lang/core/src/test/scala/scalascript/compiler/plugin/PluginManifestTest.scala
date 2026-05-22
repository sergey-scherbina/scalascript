package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite

class PluginManifestTest extends AnyFunSuite:

  test("parses a minimal backend manifest"):
    val yaml =
      """id: wasm
        |displayName: WebAssembly (wasm-tools)
        |spiVersion: "0.1.0"
        |protocol: stdio-json
        |executable: ./bin/wasm-backend
        |args: [--quiet]
        |roles: [backend]
        |backend:
        |  features: [PatternMatching, MutableState]
        |  outputs: [WasmBytecode]
        |  acceptedSources: [wat]
        |""".stripMargin
    val m = PluginManifest.parseString(yaml).get
    assert(m.id              == "wasm")
    assert(m.displayName     == "WebAssembly (wasm-tools)")
    assert(m.protocol        == "stdio-json")
    assert(m.executable      == "./bin/wasm-backend")
    assert(m.args            == List("--quiet"))
    assert(m.roles           == List("backend"))
    assert(m.features        == Set("PatternMatching", "MutableState"))
    assert(m.outputs         == Set("WasmBytecode"))
    assert(m.acceptedSources == Set("wat"))
    assert(m.isBackend)
    assert(!m.isSourceLanguage)

  test("parses a source-language plugin manifest"):
    val yaml =
      """id: python-frontend
        |displayName: Python (CPython AST)
        |spiVersion: "0.1.0"
        |protocol: stdio-json
        |executable: python3
        |args: [./bin/ssc_python.py]
        |roles: [source-language]
        |sourceLanguage:
        |  canonicalName: python
        |  aliases: [py]
        |""".stripMargin
    val m = PluginManifest.parseString(yaml).get
    assert(m.canonicalName.contains("python"))
    assert(m.aliases       == Set("py"))
    assert(!m.isBackend)
    assert(m.isSourceLanguage)

  test("parses a plugin claiming both roles"):
    val yaml =
      """id: dotnet
        |spiVersion: "0.1.0"
        |protocol: stdio-msgpack
        |executable: ./bin/ssc_dotnet
        |roles: [source-language, backend]
        |sourceLanguage:
        |  canonicalName: csharp
        |  aliases: [cs]
        |backend:
        |  features: [PatternMatching]
        |  outputs: [DotNetIL]
        |  acceptedSources: [csharp]
        |""".stripMargin
    val m = PluginManifest.parseString(yaml).get
    assert(m.isBackend)
    assert(m.isSourceLanguage)

  test("missing required field raises"):
    val yaml = """id: foo
                 |displayName: Foo
                 |""".stripMargin
    val result = PluginManifest.parseString(yaml)
    assert(result.isFailure)

  test("discover walks the search paths and parses one-level-deep manifests"):
    val sandbox = os.temp.dir(prefix = "ssc-plugin-test-")
    os.makeDir.all(sandbox / "p1")
    os.write(sandbox / "p1" / "plugin.yaml",
      """id: p1
        |displayName: Plugin 1
        |spiVersion: "0.1.0"
        |protocol: stdio-json
        |executable: ./bin/p1
        |""".stripMargin)
    os.makeDir.all(sandbox / "p2")
    os.write(sandbox / "p2" / "plugin.yaml",
      """id: p2
        |displayName: Plugin 2
        |spiVersion: "0.1.0"
        |protocol: stdio-json
        |executable: ./bin/p2
        |""".stripMargin)
    val found = PluginManifest.discover(List(sandbox))
    assert(found.map(_.id).toSet == Set("p1", "p2"))
    // Each manifest path is recorded so executablePath resolves
    // relative to its directory.
    assert(found.forall(_.manifestPath.isDefined))
    os.remove.all(sandbox)
