package scalascript.artifact

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 3 — section-level incremental cache.
 *
 *  Verifies the cumulative SHA-256 chain (Option A) that
 *  [[InterfaceExtractor.computeSectionHashes]] builds across the top-level
 *  sections of a `.ssc` module, and the [[ModuleGraph.staleSections]]
 *  helper that consumes the chain.
 *
 *  Pair-reads with `specs/v2.0-artifact-format.md` (the wire spec) and
 *  with `ModuleGraphTest` (the full-module-SHA tests it complements).
 */
class SectionIncrementalTest extends AnyFunSuite:

  private def withTempDir[A](body: os.Path => A): A =
    val d = os.temp.dir(prefix = "ssc-v2-section-test-")
    try body(d) finally os.remove.all(d)

  /** Three-section fixture used by most tests.  Each section carries a
   *  scalascript block so `computeSectionHashes` has non-empty source to
   *  digest — prose-only sections hash to a constant prefix and obscure
   *  the per-section delta.  Sections share a module-level scope: section
   *  `Main` references `helper` defined in `Lib`. */
  private def threeSectionSource(libVal: String, mainVal: String): String =
    s"""# Intro
       |
       |```scalascript
       |val intro = 0
       |```
       |
       |# Lib
       |
       |```scalascript
       |val helper = $libVal
       |```
       |
       |# Main
       |
       |```scalascript
       |val result = helper + $mainVal
       |```
       |""".stripMargin

  private def writeFixture(d: os.Path, libVal: String = "1", mainVal: String = "10"): os.Path =
    val src = d / "m.ssc"
    val srcBytes = threeSectionSource(libVal, mainVal).getBytes("UTF-8")
    os.write(src, srcBytes)
    val module = scalascript.parser.Parser.parse(new String(srcBytes, "UTF-8"))
    val iface  = InterfaceExtractor.extract(module, srcBytes)
    ArtifactIO.writeInterfaceFile(iface, d / "m.scim")
    src

  // ── 1. Hash chain populated ─────────────────────────────────────────────

  test("computeSectionHashes — 3-section module produces 3 non-trivial SHA-256 entries"):
    withTempDir { _ =>
      val srcBytes = threeSectionSource("1", "10").getBytes("UTF-8")
      val module   = scalascript.parser.Parser.parse(new String(srcBytes, "UTF-8"))
      val iface    = InterfaceExtractor.extract(module, srcBytes)
      assert(iface.sectionHashes.size == 3,
        s"expected 3 entries (Intro:0, Lib:1, Main:2), got ${iface.sectionHashes.keys.toList}")
      // Stable IDs: heading text + 0-based index.
      assert(iface.sectionHashes.contains("Intro:0"))
      assert(iface.sectionHashes.contains("Lib:1"))
      assert(iface.sectionHashes.contains("Main:2"))
      // Each value is a 64-char lowercase hex SHA-256 digest.
      iface.sectionHashes.values.foreach { h =>
        assert(h.length == 64, s"hash must be 64 hex chars, got ${h.length}: '$h'")
        assert(h.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')),
          s"hash must be lowercase hex, got '$h'")
      }
      // All three hashes are distinct because each section contains a
      // unique fenced block.
      val distinct = iface.sectionHashes.values.toSet
      assert(distinct.size == 3, s"expected 3 distinct hashes, got: $distinct")
    }

  // ── 2. Recompile unchanged source ───────────────────────────────────────

  test("staleSections — unchanged source ⇒ empty list"):
    withTempDir { d =>
      val src = writeFixture(d)
      val stale = ModuleGraph.staleSections(src, d)
      assert(stale.isEmpty, s"unchanged module should report no stale sections, got $stale")
    }

  // ── 3. Change last section ──────────────────────────────────────────────

  test("staleSections — changing only the LAST section ⇒ returns [Main:2]"):
    withTempDir { d =>
      val src = writeFixture(d, libVal = "1", mainVal = "10")
      // Edit only the trailing section's body.
      val mutated = threeSectionSource(libVal = "1", mainVal = "999").getBytes("UTF-8")
      os.write.over(src, mutated)
      val stale = ModuleGraph.staleSections(src, d)
      assert(stale == List("Main:2"),
        s"changing only the last section should yield only [Main:2], got $stale")
    }

  // ── 4. Change first section — cumulative cascade ────────────────────────

  test("staleSections — changing the FIRST section ⇒ ALL sections stale (cumulative cascade)"):
    withTempDir { d =>
      val src = writeFixture(d)
      // Change the Intro section's code-block body — Lib/Main are
      // textually identical but their cumulative-chain inputs changed.
      val mutated = """# Intro
                      |
                      |```scalascript
                      |val intro = 999
                      |```
                      |
                      |# Lib
                      |
                      |```scalascript
                      |val helper = 1
                      |```
                      |
                      |# Main
                      |
                      |```scalascript
                      |val result = helper + 10
                      |```
                      |""".stripMargin.getBytes("UTF-8")
      os.write.over(src, mutated)
      val stale = ModuleGraph.staleSections(src, d)
      assert(stale == List("Intro:0", "Lib:1", "Main:2"),
        s"changing the first section must cascade to all later sections, got $stale")
    }

  // ── 5. Add a new section ────────────────────────────────────────────────

  test("staleSections — adding a new section ⇒ new section and every subsequent section stale"):
    withTempDir { d =>
      val src = writeFixture(d)
      // Insert a new section between Lib and Main.  In Option A every
      // section from the insertion point onward is invalidated because
      // the cumulative chain shifts.
      val mutated = """# Intro
                      |
                      |```scalascript
                      |val intro = 0
                      |```
                      |
                      |# Lib
                      |
                      |```scalascript
                      |val helper = 1
                      |```
                      |
                      |# Middle
                      |
                      |```scalascript
                      |val mid = 5
                      |```
                      |
                      |# Main
                      |
                      |```scalascript
                      |val result = helper + 10
                      |```
                      |""".stripMargin.getBytes("UTF-8")
      os.write.over(src, mutated)
      val stale = ModuleGraph.staleSections(src, d).toSet
      // New keys: Middle:2 (where Main:2 used to live), Main:3.
      // Intro:0 and Lib:1 are textually unchanged AND their cumulative
      // chain prefix is unchanged, so they stay fresh.
      assert(stale.contains("Middle:2"),
        s"the new section should appear in stale list, got $stale")
      assert(stale.contains("Main:3"),
        s"the relocated 'Main' (now at index 3) is a *new* ID and must be stale, got $stale")
      assert(!stale.contains("Intro:0"),
        s"the unchanged Intro should NOT be stale, got $stale")
      assert(!stale.contains("Lib:1"),
        s"the unchanged Lib should NOT be stale, got $stale")
    }

  // ── 6. Remove the last section ──────────────────────────────────────────

  test("staleSections — removing the LAST section ⇒ surviving sections remain fresh"):
    withTempDir { d =>
      val src = writeFixture(d)
      val mutated = """# Intro
                      |
                      |```scalascript
                      |val intro = 0
                      |```
                      |
                      |# Lib
                      |
                      |```scalascript
                      |val helper = 1
                      |```
                      |""".stripMargin.getBytes("UTF-8")
      os.write.over(src, mutated)
      val stale = ModuleGraph.staleSections(src, d)
      // Intro:0 and Lib:1 are textually identical AND their cumulative
      // chain prefix is unchanged → both fresh.  The stored map still
      // contains a Main:2 entry but staleSections only reports IDs in
      // the *current* source; deletions are silently dropped.
      assert(stale.isEmpty,
        s"removing the trailing section should leave earlier sections fresh, got $stale")
    }

  // ── 7. Backward compat — empty stored map ⇒ everything stale ────────────

  test("staleSections — pre-Phase-3 .scim (empty sectionHashes) ⇒ all current sections reported stale"):
    withTempDir { d =>
      val src = d / "m.ssc"
      val srcBytes = threeSectionSource("1", "10").getBytes("UTF-8")
      os.write(src, srcBytes)
      // Synthesize a pre-Phase-3 .scim by writing an interface whose
      // sectionHashes is explicitly Map.empty — matches what the
      // upickle-driven backward-compat path produces when an older
      // artifact (without the field) is read.
      val module = scalascript.parser.Parser.parse(new String(srcBytes, "UTF-8"))
      val iface  = InterfaceExtractor.extract(module, srcBytes)
        .copy(sectionHashes = Map.empty) // simulate legacy artifact
      ArtifactIO.writeInterfaceFile(iface, d / "m.scim")
      val stale = ModuleGraph.staleSections(src, d)
      // Every current section must be reported stale so the consumer
      // forces a full re-emit — preserves pre-Phase-3 behaviour.
      assert(stale == List("Intro:0", "Lib:1", "Main:2"),
        s"legacy artifact must yield full-stale list, got $stale")
    }

  // ── Bonus — sanity check on the cumulative-chain semantics ─────────────

  test("computeSectionHashes — same first two sections + different third ⇒ first two hashes stable"):
    val src1 = threeSectionSource("1", "10").getBytes("UTF-8")
    val src2 = threeSectionSource("1", "999").getBytes("UTF-8")
    val m1 = scalascript.parser.Parser.parse(new String(src1, "UTF-8"))
    val m2 = scalascript.parser.Parser.parse(new String(src2, "UTF-8"))
    val h1 = InterfaceExtractor.computeSectionHashes(m1)
    val h2 = InterfaceExtractor.computeSectionHashes(m2)
    // Intro and Lib are textually identical AND the cumulative prefix
    // ("" then h1("Intro:0")) is identical, so their hashes match.
    assert(h1("Intro:0") == h2("Intro:0"), "Intro hash must be stable")
    assert(h1("Lib:1")   == h2("Lib:1"),   "Lib hash must be stable (prior chain unchanged)")
    // Main changes because its own source changed.
    assert(h1("Main:2") != h2("Main:2"),
      "Main hash must differ when its own source body changes")

  test("computeSectionHashes — same third section + different second ⇒ second AND third differ"):
    val src1 = threeSectionSource("1", "10").getBytes("UTF-8")
    val src2 = threeSectionSource("999", "10").getBytes("UTF-8")
    val m1 = scalascript.parser.Parser.parse(new String(src1, "UTF-8"))
    val m2 = scalascript.parser.Parser.parse(new String(src2, "UTF-8"))
    val h1 = InterfaceExtractor.computeSectionHashes(m1)
    val h2 = InterfaceExtractor.computeSectionHashes(m2)
    assert(h1("Intro:0") == h2("Intro:0"), "Intro hash unchanged (still first in chain)")
    assert(h1("Lib:1")   != h2("Lib:1"),   "Lib body changed, hash must differ")
    assert(h1("Main:2") != h2("Main:2"),
      "Main hash must cascade-change when its prior chain changed")
