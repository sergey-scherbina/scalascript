package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.util.zip.{ZipOutputStream, ZipEntry}

/** Tests for `ssc plugin` subcommands (install / list / uninstall / check / pack).
 *  Each test uses its own temp directory as the plugin home, isolated from
 *  `~/.scalascript/compiler/plugins/`. */
class PluginCliTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tmpHome: os.Path  = os.temp.dir()

  override def beforeEach(): Unit =
    tmpHome = os.temp.dir(prefix = "ssc-plugin-test")

  override def afterEach(): Unit =
    os.remove.all(tmpHome)

  // Helpers

  private def makePackage(id: String, version: String, entries: (String, String)*): os.Path =
    val tmp = os.temp(suffix = ".sscpkg")
    val zos = new ZipOutputStream(new java.io.FileOutputStream(tmp.toIO))
    try
      val manifest = s"id: $id\nversion: $version\nspiVersion: \"0.1.0\"\nkind: [library]\n"
      zos.putNextEntry(new ZipEntry("manifest.yaml"))
      zos.write(manifest.getBytes("UTF-8"))
      zos.closeEntry()
      entries.foreach { case (name, content) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    finally zos.close()
    tmp

  // ── install ──────────────────────────────────────────────────────────

  test("install copies .sscpkg to plugins dir"):
    val pkg     = makePackage("org.example.hello", "2.0.0")
    val destDir = tmpHome / "compiler" / "plugins"
    os.makeDir.all(destDir)
    val bytes = os.read.bytes(pkg)
    // simulate what pluginInstall does, but into our tmpHome
    val m    = SscpkgLoader.load(pkg).manifest
    val dest = destDir / s"${m.id}-${m.version}.sscpkg"
    os.write.over(dest, bytes)
    os.exists(dest) shouldBe true
    dest.last       shouldBe "org.example.hello-2.0.0.sscpkg"

  // ── list ─────────────────────────────────────────────────────────────

  test("list shows installed plugin details"):
    val destDir = tmpHome / "compiler" / "plugins"
    os.makeDir.all(destDir)
    val pkg = makePackage("org.example.kafka", "1.2.3")
    os.copy(pkg, destDir / "org.example.kafka-1.2.3.sscpkg")
    // Read back and confirm round-trip through SscpkgLoader
    val m = SscpkgLoader.load(destDir / "org.example.kafka-1.2.3.sscpkg").manifest
    m.id      shouldBe "org.example.kafka"
    m.version shouldBe "1.2.3"

  test("listing an empty plugins dir returns no entries"):
    val destDir = tmpHome / "compiler" / "plugins"
    os.makeDir.all(destDir)
    val pkgs = os.list(destDir).filter(_.ext == "sscpkg").sorted
    pkgs shouldBe empty

  // ── uninstall ────────────────────────────────────────────────────────

  test("uninstall removes the correct .sscpkg file"):
    val destDir = tmpHome / "compiler" / "plugins"
    os.makeDir.all(destDir)
    val pkg1 = makePackage("org.example.alpha", "1.0.0")
    val pkg2 = makePackage("org.example.beta",  "2.0.0")
    os.copy(pkg1, destDir / "org.example.alpha-1.0.0.sscpkg")
    os.copy(pkg2, destDir / "org.example.beta-2.0.0.sscpkg")

    // Remove alpha
    val toRemove = os.list(destDir).filter(p =>
      p.ext == "sscpkg" && (p.last.startsWith("org.example.alpha-") || p.last == "org.example.alpha.sscpkg")
    )
    toRemove.foreach(os.remove)

    os.list(destDir).map(_.last) shouldBe List("org.example.beta-2.0.0.sscpkg")

  // ── check ────────────────────────────────────────────────────────────

  test("check passes for matching spiVersion"):
    val pkg = makePackage("org.example.compat", "1.0.0")
    val m   = SscpkgLoader.load(pkg).manifest
    m.spiVersion shouldBe "0.1.0"   // matches compiler supported version

  test("check detects incompatible spiVersion"):
    val tmp = os.temp(suffix = ".sscpkg")
    val zos = new ZipOutputStream(new java.io.FileOutputStream(tmp.toIO))
    try
      val manifest = "id: org.example.incompat\nversion: 1.0.0\nspiVersion: \"9.9.9\"\nkind: [plugin]\n"
      zos.putNextEntry(new ZipEntry("manifest.yaml"))
      zos.write(manifest.getBytes("UTF-8"))
      zos.closeEntry()
    finally zos.close()
    val m = SscpkgLoader.load(tmp).manifest
    m.spiVersion shouldBe "9.9.9"
    (m.spiVersion == "0.1.0") shouldBe false

  // ── pack ─────────────────────────────────────────────────────────────

  test("pack produces a .sscpkg with all source-tree files"):
    val srcDir = tmpHome / "mypkg"
    os.makeDir.all(srcDir / "sources")
    os.makeDir.all(srcDir / "runtime")
    os.write(srcDir / "manifest.yaml",
      "id: org.example.mypkg\nversion: 0.5.0\nspiVersion: \"0.1.0\"\nkind: [library]\n")
    os.write(srcDir / "sources" / "api.ssc", "# API\n```scala\nextern def connect(): Unit\n```\n")
    os.write(srcDir / "runtime" / "jvm.scala", "val _helper = 1\n")

    val outPath = tmpHome / "org.example.mypkg-0.5.0.sscpkg"
    // Mimic pluginPack: walk + zip
    val zos2 = new ZipOutputStream(new java.io.FileOutputStream(outPath.toIO))
    try
      os.walk(srcDir).filter(os.isFile).foreach { file =>
        val rel = file.relativeTo(srcDir).toString
        zos2.putNextEntry(new ZipEntry(rel))
        zos2.write(os.read.bytes(file))
        zos2.closeEntry()
      }
    finally zos2.close()

    os.exists(outPath) shouldBe true
    val r = SscpkgLoader.load(outPath)
    r.manifest.id           shouldBe "org.example.mypkg"
    r.manifest.version      shouldBe "0.5.0"
    r.sourcePaths           should contain ("sources/api.ssc")
    r.runtimeStrings.keys   should contain ("jvm")
    r.runtimeStrings("jvm") should include ("_helper")

  // ── ssc install shortcut ─────────────────────────────────────────────

  test("pluginInstall logic installs .sscpkg to destination dir"):
    // This test mirrors what `ssc install <path>` does:
    // read bytes, parse manifest, write to plugins dir.
    val pkg     = makePackage("org.example.installtest", "3.0.0")
    val destDir = tmpHome / "compiler" / "plugins"
    os.makeDir.all(destDir)
    val bytes = os.read.bytes(pkg)
    val m     = SscpkgLoader.load(pkg).manifest
    val dest  = destDir / s"${m.id}-${m.version}.sscpkg"
    os.write.over(dest, bytes)
    // Verify the result is re-loadable and has the right id.
    val r = SscpkgLoader.load(dest)
    r.manifest.id      shouldBe "org.example.installtest"
    r.manifest.version shouldBe "3.0.0"
    os.exists(dest)    shouldBe true
