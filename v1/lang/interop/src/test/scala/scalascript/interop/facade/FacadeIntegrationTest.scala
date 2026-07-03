package scalascript.interop.facade

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.artifact.{ArtifactIO, InterfaceExtractor}

/** End-to-end integration tests for Tier 1 + Tier 2 + Tier 5.
 *
 *    .ssc source  --Parser-->  Module
 *                 --Extractor->  ModuleInterface (with scalaFacade)
 *                 --ArtifactIO->  on-disk .scim
 *                 --FacadeGen->  Scala source (when needed)
 *
 *  After Tier 5, JvmGen's `--bytecode` emit produces real
 *  `package x.y:` clauses, so the natural FQN equals the JVM symbol
 *  for `package:`-decorated modules.  The facade table becomes the
 *  identity for those; FacadeGenerator skips emission since no
 *  `export` is needed — a Scala consumer just `import`s naturally. */
class FacadeIntegrationTest extends AnyFunSuite:

  private def parseAndExtract(ssc: String) =
    val module = Parser.parse(ssc)
    val bytes  = ssc.getBytes("UTF-8")
    InterfaceExtractor.extract(module, bytes)

  test("end-to-end: package: + def → identity facade, no source needed"):
    val ssc =
      """---
        |package: std.eq
        |---
        |
        |# Eq
        |
        |```scalascript
        |def eqv(a: Int, b: Int): Boolean = a == b
        |def neqv(a: Int, b: Int): Boolean = a != b
        |```
        |""".stripMargin
    val iface = parseAndExtract(ssc)

    // Tier 5: facade is identity for `package:`-decorated modules.
    assert(iface.scalaFacade.nonEmpty, s"empty facade in extracted iface: $iface")
    assert(iface.scalaFacade("std.eq.eqv")  == "std.eq.eqv")
    assert(iface.scalaFacade("std.eq.neqv") == "std.eq.neqv")

    // FacadeGenerator skips identity entries — no facade source needed.
    val sources = FacadeGenerator.generateFromInterfaces(List(iface))
    assert(sources.isEmpty,
      s"post-Tier-5 identity facade should produce no source; got: ${sources.keySet}")

  test("end-to-end: empty package: ⇒ empty facade (unreachable from named pkgs)"):
    val ssc =
      """---
        |name: root
        |---
        |
        |# Root
        |
        |```scalascript
        |def hello(): Int = 42
        |```
        |""".stripMargin
    val iface = parseAndExtract(ssc)
    // No `package:` ⇒ scalaFacade is empty (Scala 3 forbids named-pkg
    // consumers from importing empty-package members).
    assert(iface.scalaFacade.isEmpty,
      s"no-package module should produce empty facade; got: ${iface.scalaFacade}")

    val sources = FacadeGenerator.generateFromInterfaces(List(iface))
    assert(sources.isEmpty,
      s"no facade entries ⇒ no sources; got: ${sources.keySet}")

  test("end-to-end: exports: front-matter filter applies to facade"):
    val ssc =
      """---
        |package: org.acme
        |exports:
        |  - publik
        |---
        |
        |# T
        |
        |```scalascript
        |def publik(): Int = 1
        |def privat(): Int = 2
        |```
        |""".stripMargin
    val iface = parseAndExtract(ssc)
    // The facade respects `exports:` filtering: only `publik` appears,
    // even though both are top-level defs.
    assert(iface.scalaFacade.keySet == Set("org.acme.publik"),
      s"private helper must NOT appear in facade; got: ${iface.scalaFacade.keySet}")
    assert(iface.scalaFacade("org.acme.publik") == "org.acme.publik",
      s"identity expected; got: ${iface.scalaFacade("org.acme.publik")}")

  test("end-to-end: on-disk round-trip — .scim written then read"):
    val tmp = os.temp.dir(prefix = "ssc-interop-int-")
    try
      val ssc =
        """---
          |package: std.x
          |---
          |
          |# X
          |
          |```scalascript
          |def f(): Unit = ()
          |```
          |""".stripMargin
      val iface = parseAndExtract(ssc)
      ArtifactIO.writeInterfaceFile(iface, tmp / "x.scim")
      val readIface = ArtifactIO.readInterfaceFile(tmp / "x.scim").toOption.get
      assert(readIface.scalaFacade == iface.scalaFacade,
        s"on-disk round-trip should preserve identity facade")
      assert(readIface.scalaFacade("std.x.f") == "std.x.f")

      // Generator over the on-disk artifact returns empty (identity → no source).
      val sources = FacadeGenerator.generate(tmp)
      assert(sources.isEmpty,
        s"Tier-5 identity facade ⇒ no source files; got: ${sources.keySet}")
    finally os.remove.all(tmp)

  test("end-to-end: legacy artifact (_ssc_runtime-prefixed) still gets a facade"):
    // Backward-compat: a `.scim` written by a pre-Tier-5 build still
    // contains `_ssc_runtime.X` mangling.  FacadeGenerator still emits
    // a re-export file for those, so the interop library stays useful
    // for mixed artifact sets.
    import scalascript.ir.*
    val legacyIface = ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = List("legacy", "pkg"),
      moduleName    = Some("legacy"),
      moduleVersion = None,
      sourceHash    = "0" * 64,
      exports       = List(ExportedSymbol("foo", "legacy_pkg_foo", "def")),
      scalaFacade   = Map("legacy.pkg.foo" -> "_ssc_runtime.legacy_pkg_foo")
    )
    val sources = FacadeGenerator.generateFromInterfaces(List(legacyIface))
    assert(sources.contains("legacy/pkg.scala"),
      s"legacy-mangled entry should still produce a facade file; got: ${sources.keySet}")
    assert(sources("legacy/pkg.scala").contains("export Ssc.legacy_pkg_foo as foo"),
      s"legacy entry should emit `export Ssc.X as Y`; got:\n${sources("legacy/pkg.scala")}")
