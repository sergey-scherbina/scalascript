package scalascript.interop.facade

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.artifact.{ArtifactIO, InterfaceExtractor}

/** End-to-end integration test for Tier 1 + Tier 2:
 *
 *    .ssc source  --Parser-->  Module
 *                 --Extractor->  ModuleInterface (with scalaFacade)
 *                 --ArtifactIO->  on-disk .scim
 *                 --FacadeGen->  Scala source
 *
 *  Verifies the data flows through every layer without mismatch and
 *  produces text that would compile under Scala 3 (manual inspection
 *  of the generated `export` lines — we don't actually run scalac
 *  here since that requires the runtime classes on classpath). */
class FacadeIntegrationTest extends AnyFunSuite:

  private def parseAndExtract(ssc: String) =
    val module = Parser.parse(ssc)
    val bytes  = ssc.getBytes("UTF-8")
    InterfaceExtractor.extract(module, bytes)

  test("end-to-end: package: + def → facade emits the right export line"):
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

    // Tier 1: facade table populated.
    assert(iface.scalaFacade.nonEmpty, s"empty facade in extracted iface: $iface")
    assert(iface.scalaFacade.contains("std.eq.eqv"))
    assert(iface.scalaFacade.contains("std.eq.neqv"))

    // Tier 2: facade generator emits one Scala file with both exports.
    val sources = FacadeGenerator.generateFromInterfaces(List(iface))
    assert(sources.keySet == Set("std/eq.scala"))
    val src = sources("std/eq.scala")
    assert(src.contains("package std.eq:"))
    assert(src.contains("export Ssc.std_eq_eqv as eqv"))
    assert(src.contains("export Ssc.std_eq_neqv as neqv"))

  test("end-to-end: empty package: ⇒ root facade file"):
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
    val sources = FacadeGenerator.generateFromInterfaces(List(iface))
    assert(sources.contains("_root_.scala"),
      s"expected root-level file, got: ${sources.keySet}")
    val src = sources("_root_.scala")
    // Simplified-export form (leaf == runtime member name).
    assert(src.contains("export Ssc.hello"),
      s"expected `export Ssc.hello`; got:\n$src")

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
    val src   = FacadeGenerator.generateFromInterfaces(List(iface))("org/acme.scala")
    assert(src.contains("as publik"),
      s"public symbol must be exported: $src")
    assert(!src.contains("as privat"),
      s"private helper must NOT be in facade:\n$src")

  test("end-to-end: on-disk round-trip — .scim written then read produces same facade"):
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

      // Read back from disk via FacadeGenerator.generate (the path
      // the SBT plugin / `ssc link --emit-scala-facade` would use).
      val sources = FacadeGenerator.generate(tmp)
      assert(sources.contains("std/x.scala"))
      assert(sources("std/x.scala").contains("export Ssc.std_x_f as f"))
    finally os.remove.all(tmp)
