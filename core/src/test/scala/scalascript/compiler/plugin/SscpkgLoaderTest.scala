package scalascript.compiler.plugin

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import java.util.zip.{ZipOutputStream, ZipEntry}

class SscpkgLoaderTest extends AnyFunSuite with Matchers:

  private def makePackage(entries: (String, String)*): os.Path =
    val tmp = os.temp(suffix = ".sscpkg")
    val zos = new ZipOutputStream(new java.io.FileOutputStream(tmp.toIO))
    try
      entries.foreach { case (name, content) =>
        zos.putNextEntry(new ZipEntry(name))
        zos.write(content.getBytes("UTF-8"))
        zos.closeEntry()
      }
    finally zos.close()
    tmp

  // ── manifest parsing via loader ────────────────────────────────

  test("load minimal package — manifest only"):
    val pkg = makePackage(
      "manifest.yaml" -> "id: org.example.mini\n"
    )
    val r = SscpkgLoader.load(pkg)
    r.manifest.id         shouldBe "org.example.mini"
    r.intrinsicJars       shouldBe Nil
    r.runtimeStrings      shouldBe Map.empty
    r.sourcePaths         shouldBe Nil

  test("load package with runtime helpers"):
    val pkg = makePackage(
      "manifest.yaml"   -> "id: org.example.rt\n",
      "runtime/jvm.scala" -> "// jvm helper\ndef _foo() = 42\n",
      "runtime/js.js"     -> "// js helper\nfunction _foo() { return 42; }\n",
    )
    val r = SscpkgLoader.load(pkg)
    r.runtimeStrings.keys should contain allOf ("jvm", "js")
    r.runtimeStrings("jvm") should include ("_foo")
    r.runtimeStrings("js")  should include ("_foo")

  test("load package with sources"):
    val pkg = makePackage(
      "manifest.yaml"          -> "id: org.example.lib\n",
      "sources/std/kafka.ssc"  -> "# Kafka\n```scala\nextern def connect(): Unit\n```\n",
      "sources/std/other.ssc"  -> "# Other\n",
    )
    val r = SscpkgLoader.load(pkg)
    r.sourcePaths should contain allOf (
      "sources/std/kafka.ssc",
      "sources/std/other.ssc",
    )

  test("missing manifest.yaml throws"):
    val pkg = makePackage(
      "runtime/jvm.scala" -> "// no manifest\n"
    )
    an [RuntimeException] shouldBe thrownBy { SscpkgLoader.load(pkg) }

  // ── BackendRegistry integration ────────────────────────────────

  test("loadSscpkg registers runtime preamble in BackendRegistry"):
    BackendRegistry.reload()
    val pkg = makePackage(
      "manifest.yaml"     -> "id: org.example.preamble\n",
      "runtime/jvm.scala" -> "val _testHelper = 1\n",
    )
    BackendRegistry.loadSscpkg(pkg)
    BackendRegistry.preambleFor("jvm") should include ("_testHelper")
    BackendRegistry.reload()

  test("preamble accumulates across multiple packages"):
    BackendRegistry.reload()
    val pkg1 = makePackage(
      "manifest.yaml"     -> "id: org.example.a\n",
      "runtime/jvm.scala" -> "val _a = 1\n",
    )
    val pkg2 = makePackage(
      "manifest.yaml"     -> "id: org.example.b\n",
      "runtime/jvm.scala" -> "val _b = 2\n",
    )
    BackendRegistry.loadSscpkg(pkg1)
    BackendRegistry.loadSscpkg(pkg2)
    val p = BackendRegistry.preambleFor("jvm")
    p should include ("_a")
    p should include ("_b")
    BackendRegistry.reload()

  // ── Dependency resolution (Tier 3) ────────────────────────────────

  test("manifest parses dependencies map"):
    val yaml = """
      |id: org.example.main
      |dependencies:
      |  org.example.dep: "^1.0"
      |  org.example.other: "*"
      |""".stripMargin
    val m = SscpkgManifest.parseString(yaml).get
    m.dependencies shouldBe Map("org.example.dep" -> "^1.0", "org.example.other" -> "*")

  test("manifest parses dependencies list"):
    val yaml = """
      |id: org.example.main
      |dependencies:
      |  - id: org.example.dep
      |    version: "^1.0"
      |""".stripMargin
    val m = SscpkgManifest.parseString(yaml).get
    m.dependencies shouldBe Map("org.example.dep" -> "^1.0")

  test("loadSscpkg loads transitive dependency when present in extra dir"):
    BackendRegistry.reload()
    val tmpDir = os.temp.dir()
    // dep package
    val depPkg = makePackage(
      "manifest.yaml"     -> "id: org.example.dep\n",
      "runtime/jvm.scala" -> "val _dep = 99\n",
    )
    os.copy(depPkg, tmpDir / "org.example.dep-0.1.0.sscpkg")
    BackendRegistry.addPluginDir(tmpDir)
    // main package depending on dep
    val mainPkg = makePackage(
      "manifest.yaml" -> "id: org.example.main\ndependencies:\n  org.example.dep: \"*\"\n",
      "runtime/jvm.scala" -> "val _main = 1\n",
    )
    BackendRegistry.loadSscpkg(mainPkg)
    val p = BackendRegistry.preambleFor("jvm")
    p should include ("_dep")
    p should include ("_main")
    BackendRegistry.reload()

  test("loadSscpkg skips already-loaded package (idempotent)"):
    BackendRegistry.reload()
    val pkg = makePackage(
      "manifest.yaml"     -> "id: org.example.idem\n",
      "runtime/jvm.scala" -> "val _idem = 1\n",
    )
    BackendRegistry.loadSscpkg(pkg)
    BackendRegistry.loadSscpkg(pkg)  // second call is a no-op
    BackendRegistry.preambleFor("jvm").count(_ == '1') shouldBe 1  // only one '1'
    BackendRegistry.reload()

  test("loadSscpkg raises on dependency cycle"):
    BackendRegistry.reload()
    val tmpDir = os.temp.dir()
    // A depends on B; B depends on A → cycle
    val pkgA = makePackage(
      "manifest.yaml" -> "id: org.example.cycleA\ndependencies:\n  org.example.cycleB: \"*\"\n",
    )
    val pkgB = makePackage(
      "manifest.yaml" -> "id: org.example.cycleB\ndependencies:\n  org.example.cycleA: \"*\"\n",
    )
    os.copy(pkgA, tmpDir / "org.example.cycleA-0.1.0.sscpkg")
    os.copy(pkgB, tmpDir / "org.example.cycleB-0.1.0.sscpkg")
    BackendRegistry.addPluginDir(tmpDir)
    an [RuntimeException] shouldBe thrownBy {
      BackendRegistry.loadSscpkg(tmpDir / "org.example.cycleA-0.1.0.sscpkg")
    }
    BackendRegistry.reload()

  test("loadSscpkg raises when transitive dependency is missing"):
    BackendRegistry.reload()
    val pkg = makePackage(
      "manifest.yaml" -> "id: org.example.missing\ndependencies:\n  org.nonexistent.dep: \"*\"\n",
    )
    an [RuntimeException] shouldBe thrownBy { BackendRegistry.loadSscpkg(pkg) }
    BackendRegistry.reload()
