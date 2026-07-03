package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Tests for v2.0 Phase 5 — interface-based per-section incremental cache
 *  (Option B).  See `ModuleGraph.staleSectionsInterfaceBased` and
 *  `InterfaceExtractor.computeSectionInterfaceHashes`.
 *
 *  Option A (existing) cascades a body change in section 1 to all later
 *  sections.  Option B does NOT — a body-only change leaves later sections
 *  cached, because their interface input (section 1's exported shape) is
 *  unchanged. */
class SectionIncrementalInterfaceTest extends AnyFunSuite:

  // Three sections; each has one top-level `def` so we can perturb either
  // its body or its signature independently.
  private def srcV1 = """# Intro
                        |
                        |```scalascript
                        |def intro(): Int = 0
                        |```
                        |
                        |# Lib
                        |
                        |```scalascript
                        |def helper(): Int = 1
                        |```
                        |
                        |# Main
                        |
                        |```scalascript
                        |def result(): Int = helper() + 10
                        |```
                        |""".stripMargin

  // Section 1's BODY changes; signature stays the same.
  private def srcV1BodyOnly = srcV1.replace("def intro(): Int = 0", "def intro(): Int = 42")

  // Section 1's SIGNATURE changes (added a parameter).
  private def srcV1Iface = srcV1.replace("def intro(): Int = 0", "def intro(x: Int): Int = x")

  test("computeSectionInterfaceHashes — non-empty for 3-section module"):
    val module = Parser.parse(srcV1)
    val hashes = InterfaceExtractor.computeSectionInterfaceHashes(module)
    assert(hashes.size == 3, s"expected 3 entries, got: $hashes")
    hashes.values.foreach { h =>
      assert(h.length == 64, s"expected 64-char SHA-256 hex, got '$h'")
    }

  test("body-only edit on section 1 leaves sections 2/3 fresh under iface mode"):
    val v1 = Parser.parse(srcV1)
    val v2 = Parser.parse(srcV1BodyOnly)
    val iface1 = InterfaceExtractor.computeSectionInterfaceHashes(v1)
    val iface2 = InterfaceExtractor.computeSectionInterfaceHashes(v2)
    // Section 1's interface hash must NOT change when only its body changed.
    assert(iface1("Intro:0") == iface2("Intro:0"),
      s"body-only edit must not perturb iface hash; v1=${iface1("Intro:0")} v2=${iface2("Intro:0")}")
    // Sections 2 and 3 also unchanged.
    assert(iface1("Lib:1")  == iface2("Lib:1"))
    assert(iface1("Main:2") == iface2("Main:2"))

  test("signature change on section 1 perturbs its iface hash"):
    val v1 = Parser.parse(srcV1)
    val v2 = Parser.parse(srcV1Iface)
    val iface1 = InterfaceExtractor.computeSectionInterfaceHashes(v1)
    val iface2 = InterfaceExtractor.computeSectionInterfaceHashes(v2)
    assert(iface1("Intro:0") != iface2("Intro:0"),
      "signature-changing edit must perturb iface hash")
    // Section 2 and 3 own interfaces stay the same; cascade decision is
    // made by ModuleGraph.staleSectionsInterfaceBased, not here.
    assert(iface1("Lib:1")  == iface2("Lib:1"))
    assert(iface1("Main:2") == iface2("Main:2"))

  test("staleSectionsInterfaceBased — body-only edit on section 1 yields ['Intro:0']"):
    val sandbox = os.temp.dir(prefix = "ssc-section-iface-")
    try
      val srcPath = sandbox / "m.ssc"
      val artDir  = sandbox / "out"
      os.makeDir.all(artDir)
      // Write v1 and emit its .scim.
      os.write(srcPath, srcV1)
      val v1 = Parser.parse(srcV1)
      val iface = InterfaceExtractor.extract(v1, srcV1.getBytes("UTF-8"))
      ArtifactIO.writeInterfaceFile(iface, artDir / "m.scim")
      // Now mutate the source (body-only on section 1).
      os.write.over(srcPath, srcV1BodyOnly)
      val stale = ModuleGraph.staleSectionsInterfaceBased(srcPath, artDir)
      // Section 1 itself IS stale (body changed); sections 2/3 are NOT (their
      // body is unchanged AND section 1's interface didn't shift).
      assert(stale == List("Intro:0"),
        s"expected only Intro:0 stale; got $stale")
    finally os.remove.all(sandbox)

  test("staleSectionsInterfaceBased — iface-change on section 1 cascades to 2/3"):
    val sandbox = os.temp.dir(prefix = "ssc-section-iface-")
    try
      val srcPath = sandbox / "m.ssc"
      val artDir  = sandbox / "out"
      os.makeDir.all(artDir)
      os.write(srcPath, srcV1)
      val v1 = Parser.parse(srcV1)
      val iface = InterfaceExtractor.extract(v1, srcV1.getBytes("UTF-8"))
      ArtifactIO.writeInterfaceFile(iface, artDir / "m.scim")
      // Mutate the source — change section 1's signature (adds a parameter).
      os.write.over(srcPath, srcV1Iface)
      val stale = ModuleGraph.staleSectionsInterfaceBased(srcPath, artDir)
      // Section 1 stale (its body changed too).  Section 2 and 3 also stale
      // because section 1's interface changed.
      assert(stale.toSet == Set("Intro:0", "Lib:1", "Main:2"),
        s"expected all 3 stale on iface-change; got $stale")
    finally os.remove.all(sandbox)

  test("Option B never wider than Option A — strictly more permissive"):
    val sandbox = os.temp.dir(prefix = "ssc-section-iface-")
    try
      val srcPath = sandbox / "m.ssc"
      val artDir  = sandbox / "out"
      os.makeDir.all(artDir)
      os.write(srcPath, srcV1)
      val v1 = Parser.parse(srcV1)
      val iface = InterfaceExtractor.extract(v1, srcV1.getBytes("UTF-8"))
      ArtifactIO.writeInterfaceFile(iface, artDir / "m.scim")
      // For both edit shapes, Option B's stale set ⊆ Option A's stale set.
      for variant <- List(srcV1BodyOnly, srcV1Iface) do
        os.write.over(srcPath, variant)
        val a = ModuleGraph.staleSections(srcPath, artDir).toSet
        val b = ModuleGraph.staleSectionsInterfaceBased(srcPath, artDir).toSet
        assert(b.subsetOf(a),
          s"Option B should be ⊆ Option A; A=$a B=$b on variant ${variant.take(40)}")
    finally os.remove.all(sandbox)

  test("backward-compat — legacy .scim (no sectionInterfaceHashes) → all-stale safe default"):
    val sandbox = os.temp.dir(prefix = "ssc-section-iface-")
    try
      val srcPath = sandbox / "m.ssc"
      val artDir  = sandbox / "out"
      os.makeDir.all(artDir)
      os.write(srcPath, srcV1)
      val v1 = Parser.parse(srcV1)
      val iface = InterfaceExtractor.extract(v1, srcV1.getBytes("UTF-8"))
      // Strip the new field — simulate a pre-Phase-5 artifact.
      val legacy = iface.copy(sectionInterfaceHashes = Map.empty)
      ArtifactIO.writeInterfaceFile(legacy, artDir / "m.scim")

      val stale = ModuleGraph.staleSectionsInterfaceBased(srcPath, artDir)
      // No interface hashes stored → safe default: every section is stale.
      assert(stale.toSet == Set("Intro:0", "Lib:1", "Main:2"),
        s"expected all sections stale on legacy artifact; got $stale")
    finally os.remove.all(sandbox)
