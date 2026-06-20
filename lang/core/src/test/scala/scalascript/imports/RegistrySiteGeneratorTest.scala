package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite

class RegistrySiteGeneratorTest extends AnyFunSuite:

  private val e1 = RegistryEntry(
    name        = "io.scalascript/json",
    version     = "1.2.0",
    description = "JSON encoding/decoding",
    keywords    = List("json", "codec"),
    backends    = List("jvm", "js"),
    url         = "github:scalascript/stdlib",
    license     = "Apache-2.0",
    author      = "ScalaScript Core Team",
  )

  private val e2 = RegistryEntry(
    name        = "io.scalascript/http",
    version     = "0.9.1",
    description = "HTTP client and server",
    backends    = List("jvm"),
    deprecated  = true,
  )

  private val e3 = RegistryEntry(
    name    = "mylib",
    version = "0.1.0",
  )

  // ── packageJson ────────────────────────────────────────────────────────────

  test("packageJson includes all non-empty fields") {
    val json = RegistrySiteGenerator.packageJson(e1)
    assert(json.contains(""""name": "io.scalascript/json""""))
    assert(json.contains(""""version": "1.2.0""""))
    assert(json.contains(""""description": "JSON encoding/decoding""""))
    assert(json.contains(""""keywords": ["json", "codec"]"""))
    assert(json.contains(""""backends": ["jvm", "js"]"""))
    assert(json.contains(""""url": "github:scalascript/stdlib""""))
    assert(json.contains(""""license": "Apache-2.0""""))
    assert(json.contains(""""author": "ScalaScript Core Team""""))
    assert(json.contains(""""install": "dep:io.scalascript/json:1.2.0""""))
    assert(!json.contains("deprecated"))
  }

  test("packageJson omits empty optional fields") {
    val json = RegistrySiteGenerator.packageJson(e3)
    assert(json.contains(""""name": "mylib""""))
    assert(json.contains(""""version": "0.1.0""""))
    assert(!json.contains("description"))
    assert(!json.contains("keywords"))
    assert(!json.contains("backends"))
    assert(!json.contains("license"))
  }

  test("packageJson emits deprecated:true when set") {
    val json = RegistrySiteGenerator.packageJson(e2)
    assert(json.contains(""""deprecated": true"""))
  }

  test("packageJson escapes special chars in strings") {
    val e = RegistryEntry(name = "a/b", version = "1.0", description = """say "hi"\n""")
    val json = RegistrySiteGenerator.packageJson(e)
    assert(json.contains("""\"hi\""""))
    assert(json.contains("""\\n"""))
  }

  test("packageJson is valid JSON structure") {
    val json = RegistrySiteGenerator.packageJson(e1)
    assert(json.startsWith("{"))
    assert(json.trim.endsWith("}"))
    // all fields except the last end with ","
    val fields = json.trim.split("\n").drop(1).dropRight(1)
    assert(fields.init.forall(_.trim.endsWith(",")))
    assert(!fields.last.trim.endsWith(","))
  }

  // ── searchIndex ────────────────────────────────────────────────────────────

  test("searchIndex contains ref and body for each entry") {
    val idx = RegistrySiteGenerator.searchIndex(List(e1, e2))
    assert(idx.contains(""""ref": "io.scalascript/json""""))
    assert(idx.contains(""""ref": "io.scalascript/http""""))
    assert(idx.contains(""""body":"""))
    assert(idx.startsWith("["))
    assert(idx.trim.endsWith("]"))
  }

  test("searchIndex body concatenates name + description + keywords + backends") {
    val idx = RegistrySiteGenerator.searchIndex(List(e1))
    assert(idx.contains("json codec"))
    assert(idx.contains("JSON encoding"))
    assert(idx.contains("jvm"))
  }

  test("searchIndex handles empty list") {
    val idx = RegistrySiteGenerator.searchIndex(Nil)
    assert(idx.trim == "[]" || idx.trim == "[\n]" || idx.trim.replaceAll("\\s+", "") == "[]")
  }

  // ── indexHtml ──────────────────────────────────────────────────────────────

  test("indexHtml contains package count") {
    val html = RegistrySiteGenerator.indexHtml(List(e1, e2))
    assert(html.contains("2 packages"))
  }

  test("indexHtml contains table rows for all entries") {
    val html = RegistrySiteGenerator.indexHtml(List(e1, e2, e3))
    assert(html.contains("io.scalascript/json"))
    assert(html.contains("io.scalascript/http"))
    assert(html.contains("mylib"))
  }

  test("indexHtml marks deprecated entry with style") {
    val html = RegistrySiteGenerator.indexHtml(List(e2))
    assert(html.contains("opacity:0.5"))
    assert(html.contains("[deprecated]"))
  }

  test("indexHtml escapes HTML special chars in description") {
    val e = RegistryEntry(name = "x/y", version = "1.0", description = "a <b> & \"c\"")
    val html = RegistrySiteGenerator.indexHtml(List(e))
    assert(html.contains("a &lt;b&gt; &amp; &quot;c&quot;"))
    assert(!html.contains("<b>"))
  }

  test("indexHtml link path uses package name as-is") {
    val html = RegistrySiteGenerator.indexHtml(List(e1))
    assert(html.contains("""href="packages/io.scalascript/json/index.json""""))
  }

  test("indexHtml has search input and JS handler") {
    val html = RegistrySiteGenerator.indexHtml(List(e1))
    assert(html.contains("""id="search""""))
    assert(html.contains("addEventListener"))
  }

  // ── generate (filesystem) ──────────────────────────────────────────────────

  test("generate writes all expected files") {
    val tmpDir = os.temp.dir()
    try
      RegistrySiteGenerator.generate(List(e1, e3), tmpDir)
      assert(os.exists(tmpDir / "index.html"))
      assert(os.exists(tmpDir / "search-index.json"))
      assert(os.exists(tmpDir / "packages.yaml"))
      assert(os.exists(tmpDir / "packages" / "io.scalascript" / "json" / "index.json"))
      assert(os.exists(tmpDir / "packages" / "unknown" / "mylib" / "index.json"))
    finally os.remove.all(tmpDir)
  }

  test("generate writes parseable packages.yaml for the CLI default URL") {
    val tmpDir = os.temp.dir()
    try
      RegistrySiteGenerator.generate(List(e1, e2), tmpDir)
      val yaml = os.read(tmpDir / "packages.yaml")
      val entries = RegistryEntry.parseAll(yaml).toOption.getOrElse(Nil)
      assert(entries.map(_.name) == List("io.scalascript/json", "io.scalascript/http"))
    finally os.remove.all(tmpDir)
  }

  test("generate writes correct package JSON content") {
    val tmpDir = os.temp.dir()
    try
      RegistrySiteGenerator.generate(List(e1), tmpDir)
      val json = os.read(tmpDir / "packages" / "io.scalascript" / "json" / "index.json")
      assert(json.contains(""""name": "io.scalascript/json""""))
    finally os.remove.all(tmpDir)
  }
