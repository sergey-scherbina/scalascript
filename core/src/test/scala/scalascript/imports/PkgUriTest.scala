package scalascript.imports

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.util.zip.{ZipOutputStream, ZipEntry}
import scalascript.compiler.plugin.{BackendRegistry, SscpkgLoader}
import scala.compiletime.uninitialized

/** Tests for `pkg:` URI handling in `ImportResolver`.
 *
 *  Each test uses a freshly-created tmp directory registered as an extra
 *  plugin dir so it doesn't pollute `~/.scalascript/compiler/plugins/`.
 *  `BackendRegistry.reload()` is called after each test to discard state. */
class PkgUriTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var pluginsDir: os.Path = uninitialized
  private var baseDir: os.Path    = uninitialized

  override def beforeEach(): Unit =
    pluginsDir = os.temp.dir(prefix = "ssc-pkg-uri-test-plugins")
    baseDir    = os.temp.dir(prefix = "ssc-pkg-uri-test-base")
    BackendRegistry.reload()
    BackendRegistry.addPluginDir(pluginsDir)

  override def afterEach(): Unit =
    os.remove.all(pluginsDir)
    os.remove.all(baseDir)
    BackendRegistry.reload()

  // ── helpers ────────────────────────────────────────────────────────────

  /** Build a minimal `.sscpkg` with the given id/version and source files.
   *  Pass an empty map for `sources` to create a sources-free package. */
  private def makePkg(
      id:      String,
      version: String,
      sources: Map[String, String],
  ): os.Path =
    val dest = pluginsDir / s"$id-$version.sscpkg"
    val zos  = new ZipOutputStream(new java.io.FileOutputStream(dest.toIO))
    try
      val manifest = s"id: $id\nversion: $version\nspiVersion: \"0.1.0\"\nkind: [library]\n"
      zos.putNextEntry(new ZipEntry("manifest.yaml"))
      zos.write(manifest.getBytes("UTF-8"))
      zos.closeEntry()
      sources.foreach { case (name, content) =>
        zos.putNextEntry(new ZipEntry(s"sources/$name"))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    finally zos.close()
    dest

  // ── pkg: URI resolves to extracted source ──────────────────────────────

  test("pkg: URI with version resolves to index.ssc when present"):
    makePkg("org.example.mylib", "1.0.0", Map(
      "index.ssc" -> "# MyLib\n```scala\nextern def myFun(): String\n```\n",
      "other.ssc" -> "# Other\n",
    ))
    val resolved = ImportResolver.resolve("pkg:org.example.mylib:1.0.0", baseDir)
    resolved.last       shouldBe "index.ssc"
    os.exists(resolved) shouldBe true
    os.read(resolved)   should include ("myFun")

  test("pkg: URI with version falls back to first .ssc when no index.ssc"):
    makePkg("org.example.util", "2.0.0", Map(
      "api.ssc" -> "# Api\n```scala\nextern def compute(): Int\n```\n",
    ))
    val resolved = ImportResolver.resolve("pkg:org.example.util:2.0.0", baseDir)
    resolved.last       shouldBe "api.ssc"
    os.exists(resolved) shouldBe true

  test("pkg: URI without version resolves to first matching package"):
    makePkg("mypackage", "0.5.0", Map(
      "index.ssc" -> "# MyPackage\n```scala\nextern def hello(): Unit\n```\n",
    ))
    val resolved = ImportResolver.resolve("pkg:mypackage", baseDir)
    resolved.last       shouldBe "index.ssc"
    os.exists(resolved) shouldBe true

  test("pkg: URI with slash-qualified name (scalascript/json style)"):
    makePkg("scalascript.json", "1.0.0", Map(
      "index.ssc" -> "# Json\n```scala\nextern def jsonParse(s: String): Any\n```\n",
    ))
    val resolved = ImportResolver.resolve("pkg:scalascript/json:1.0.0", baseDir)
    resolved.last       shouldBe "index.ssc"
    os.exists(resolved) shouldBe true
    os.read(resolved)   should include ("jsonParse")

  // ── SscpkgLoader.extractSources ────────────────────────────────────────

  test("extractSources unpacks all .ssc files from sources/ into a temp dir"):
    val pkg = makePkg("org.example.multi", "1.0.0", Map(
      "alpha.ssc" -> "# Alpha\n",
      "beta.ssc"  -> "# Beta\n",
    ))
    val srcDir = SscpkgLoader.extractSources(pkg)
    os.isDir(srcDir) shouldBe true
    val files = os.list(srcDir).map(_.last).sorted
    files should contain allOf ("alpha.ssc", "beta.ssc")

  test("extractSources returns empty dir for package with no sources"):
    val pkg = makePkg("org.example.nosrc", "1.0.0", sources = Map.empty)
    val srcDir = SscpkgLoader.extractSources(pkg)
    os.isDir(srcDir) shouldBe true
    os.list(srcDir)  shouldBe empty

  // ── BackendRegistry.findInstalledPkg ───────────────────────────────────

  test("findInstalledPkg finds package by exact qualified id and version"):
    makePkg("org.example.redis", "2.1.0", Map("index.ssc" -> "# Redis\n"))
    BackendRegistry.findInstalledPkg("org.example.redis:2.1.0") shouldBe defined

  test("findInstalledPkg finds package by short name when org omitted"):
    makePkg("mylib", "1.0.0", Map("index.ssc" -> "# MyLib\n"))
    BackendRegistry.findInstalledPkg("mylib:1.0.0") shouldBe defined

  test("findInstalledPkg returns None when no match"):
    BackendRegistry.findInstalledPkg("nonexistent:9.9.9") shouldBe empty

  // ── error message when plugin not installed ────────────────────────────

  test("pkg: URI throws helpful error when plugin not installed and no registry entry"):
    val ex = intercept[RuntimeException] {
      ImportResolver.resolve("pkg:scalascript/missing:1.0", baseDir)
    }
    ex.getMessage should include ("not installed")
    ex.getMessage should include ("ssc install")
    ex.getMessage should include ("scalascript/missing:1.0")

  // ── pkg: URI is additive — non-pkg paths still work ────────────────────

  test("non-pkg: imports are unaffected by the new pkg: branch"):
    val sscFile = baseDir / "hello.ssc"
    os.write(sscFile, "# Hello\n")
    val resolved = ImportResolver.resolve("hello.ssc", baseDir)
    resolved shouldBe sscFile
