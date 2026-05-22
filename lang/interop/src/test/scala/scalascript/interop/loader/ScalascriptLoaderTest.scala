package scalascript.interop.loader

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.*
import scalascript.artifact.ArtifactIO

class ScalascriptLoaderTest extends AnyFunSuite:

  // ── Fixture: a minimal .scim that just declares the facade table ───────

  private def iface(
      pkg: List[String],
      moduleName: String,
      facade: Map[String, String]
  ): ModuleInterface =
    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = Some(moduleName),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = facade.keys.map(k =>
        val leaf = k.split('.').last
        ExportedSymbol(leaf, scalascript.artifact.Linker.mangle(pkg, leaf), "def")
      ).toList,
      scalaFacade   = facade
    )

  // ── fromArtifactDir — happy path ───────────────────────────────────────

  test("fromArtifactDir — empty dir yields a loader with no entries"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      val loader = ScalascriptLoader.fromArtifactDir(d)
      assert(loader.naturalNames.isEmpty)
    finally os.remove.all(d)

  test("fromArtifactDir — single .scim populates the facade index"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      val m = iface(
        pkg = List("std", "eq"),
        moduleName = "eq",
        facade = Map("std.eq.eqv" -> "_ssc_runtime.std_eq_eqv")
      )
      ArtifactIO.writeInterfaceFile(m, d / "eq.scim")
      val loader = ScalascriptLoader.fromArtifactDir(d)
      assert(loader.naturalNames == Set("std.eq.eqv"))
      assert(loader.mangle("std.eq.eqv").contains("_ssc_runtime.std_eq_eqv"))
    finally os.remove.all(d)

  test("fromArtifactDir — non-existent path throws IllegalArgumentException"):
    val ghost = os.Path("/tmp/does-not-exist-" + System.nanoTime())
    val ex = intercept[IllegalArgumentException](ScalascriptLoader.fromArtifactDir(ghost))
    assert(ex.getMessage.contains("does not exist"))

  test("fromArtifactDir — file (not directory) → IllegalArgumentException"):
    val f = os.temp(prefix = "not-a-dir-", suffix = ".tmp")
    try
      val ex = intercept[IllegalArgumentException](ScalascriptLoader.fromArtifactDir(f))
      assert(ex.getMessage.contains("not a directory"))
    finally os.remove(f)

  test("fromArtifactDir — multiple .scim files merge into one index"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      ArtifactIO.writeInterfaceFile(
        iface(List("std", "a"), "a", Map("std.a.foo" -> "_ssc_runtime.std_a_foo")),
        d / "a.scim"
      )
      ArtifactIO.writeInterfaceFile(
        iface(List("std", "b"), "b", Map("std.b.bar" -> "_ssc_runtime.std_b_bar")),
        d / "b.scim"
      )
      val loader = ScalascriptLoader.fromArtifactDir(d)
      assert(loader.naturalNames == Set("std.a.foo", "std.b.bar"),
        s"expected both modules' entries; got: ${loader.naturalNames}")
    finally os.remove.all(d)

  // ── Lookup + reverse lookup ────────────────────────────────────────────

  test("mangle / naturalFor — round-trip works on populated index"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      ArtifactIO.writeInterfaceFile(
        iface(List("p"), "p", Map("p.x" -> "_ssc_runtime.p_x")),
        d / "p.scim"
      )
      val loader = ScalascriptLoader.fromArtifactDir(d)
      assert(loader.mangle("p.x").contains("_ssc_runtime.p_x"))
      assert(loader.naturalFor("_ssc_runtime.p_x").contains("p.x"))
      assert(loader.mangle("p.unknown").isEmpty)
      assert(loader.naturalFor("_ssc_runtime.no_such").isEmpty)
    finally os.remove.all(d)

  // ── call — error cases (no real .ssc-runtime JAR loaded) ───────────────

  test("call — unknown natural FQN → NoSuchScalascriptSymbol"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      val loader = ScalascriptLoader.fromArtifactDir(d)
      val ex = intercept[NoSuchScalascriptSymbol](loader.call[Int]("p.unknown"))
      assert(ex.naturalFqn == "p.unknown")
    finally os.remove.all(d)

  test("call — known facade entry but missing class on classpath → UnresolvedJvmMember"):
    val d = os.temp.dir(prefix = "ssc-interop-loader-")
    try
      ArtifactIO.writeInterfaceFile(
        iface(List("p"), "p", Map("p.x" -> "_ssc_runtime.p_x")),
        d / "p.scim"
      )
      val loader = ScalascriptLoader.fromArtifactDir(d)
      val ex = intercept[UnresolvedJvmMember](loader.call[Int]("p.x"))
      assert(ex.mangledFqn == "_ssc_runtime.p_x",
        s"expected mangled FQN in error; got: ${ex.mangledFqn}")
    finally os.remove.all(d)

  // ── fromJar — error cases (we don't build a real JAR here) ─────────────

  test("fromJar — non-existent path throws IllegalArgumentException"):
    val ghost = os.Path("/tmp/no-such-jar-" + System.nanoTime() + ".jar")
    val ex = intercept[IllegalArgumentException](ScalascriptLoader.fromJar(ghost))
    assert(ex.getMessage.contains("does not exist"))

  test("fromJar — JAR without embedded .scim resources yields an empty index"):
    // Build a tiny empty JAR to feed the reader; it shouldn't crash.
    val f = os.temp(prefix = "empty-", suffix = ".jar")
    try
      val zos = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(f.toNIO))
      zos.close()
      val loader = ScalascriptLoader.fromJar(f)
      assert(loader.naturalNames.isEmpty,
        s"empty JAR → empty index; got: ${loader.naturalNames}")
    finally os.remove(f)
