package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Tests for the incremental type-checking API introduced in
 *  [[Typer.typeCheckIncremental]] / [[Typer.typeCheckIncrementalModule]].
 *
 *  The core invariant: only sections whose content hash changed (compared to
 *  the previous run's [[SectionSnapshot]] list) are re-typed.  Sections before
 *  the first change are reused from the previous snapshot verbatim.
 */
class IncrementalTypingTest extends AnyFunSuite:

  // ─── Helpers ────────────────────────────────────────────────────────────────

  /** Build a 3-section module. */
  private def threeSection(
      body1: String = "val x: Int = 1",
      body2: String = "val y: Int = 2",
      body3: String = "val z: Int = 3"
  ): scalascript.ast.Module =
    Parser.parse(
      s"""# Section1
         |
         |```scalascript
         |$body1
         |```
         |
         |# Section2
         |
         |```scalascript
         |$body2
         |```
         |
         |# Section3
         |
         |```scalascript
         |$body3
         |```
         |""".stripMargin
    )

  /** Run typeCheckIncrementalModule and return (typedModule, newSnapshots). */
  private def run(
      mod: scalascript.ast.Module,
      prev: List[SectionSnapshot] = Nil
  ): (TypedModule, List[SectionSnapshot]) =
    Typer.typeCheckIncrementalModule(mod, prev)

  // ─── Test: cold start (no previous snapshots) ───────────────────────────────

  test("cold start: all 3 sections are typed and snapshots are produced"):
    val mod = threeSection()
    val (tm, snaps) = run(mod)
    assert(snaps.length == 3, s"expected 3 snapshots, got ${snaps.length}")
    assert(tm.sections.length == 3)
    assert(!tm.hasErrors, s"unexpected errors: ${tm.errors}")

  // ─── Test: no changes → zero sections re-typed ──────────────────────────────

  test("no changes: second run reuses all snapshots, no re-typing"):
    val mod = threeSection()
    val (_, snaps1) = run(mod)

    // Capture names produced by re-typing to verify none are re-computed.
    // We verify this indirectly: the TypedSections in the new snapshots must
    // be *the same object references* as those in snaps1 (carried forward).
    val (_, snaps2) = run(mod, snaps1)

    assert(snaps2.length == 3)
    // Object identity check: unchanged TypedSections are reused as-is.
    snaps1.zip(snaps2).foreach { (s1, s2) =>
      assert(s1.typedSection eq s2.typedSection,
        "Expected same TypedSection reference for unchanged section")
    }

  // ─── Test: only last section changed ────────────────────────────────────────

  test("change last section: only section 3 is re-typed; sections 1 & 2 are reused"):
    val modOrig = threeSection(body3 = "val z: Int = 3")
    val (_, snaps1) = run(modOrig)

    val modNew = threeSection(body3 = "val z: Int = 99")
    val (tm2, snaps2) = run(modNew, snaps1)

    assert(snaps2.length == 3)
    // Sections 1 and 2 must be the exact same object references.
    assert(snaps1(0).typedSection eq snaps2(0).typedSection,
      "Section 1 should not be re-typed")
    assert(snaps1(1).typedSection eq snaps2(1).typedSection,
      "Section 2 should not be re-typed")
    // Section 3 must be a new object (re-typed).
    assert(!(snaps1(2).typedSection eq snaps2(2).typedSection),
      "Section 3 should have been re-typed")
    assert(!tm2.hasErrors, s"unexpected errors: ${tm2.errors}")

  // ─── Test: change first section → all sections re-typed ─────────────────────

  test("change first section: all 3 sections are re-typed"):
    val modOrig = threeSection(body1 = "val x: Int = 1")
    val (_, snaps1) = run(modOrig)

    val modNew = threeSection(body1 = "val x: Int = 42")
    val (tm2, snaps2) = run(modNew, snaps1)

    assert(snaps2.length == 3)
    // All three TypedSection references must be fresh (re-typed).
    snaps1.zip(snaps2).foreach { (s1, s2) =>
      assert(!(s1.typedSection eq s2.typedSection),
        "All sections should have been re-typed after a change in section 1")
    }
    assert(!tm2.hasErrors, s"unexpected errors: ${tm2.errors}")

  // ─── Test: change middle section ────────────────────────────────────────────

  test("change middle section: section 1 reused; sections 2 & 3 re-typed"):
    val modOrig = threeSection(body2 = "val y: Int = 2")
    val (_, snaps1) = run(modOrig)

    val modNew = threeSection(body2 = "val y: Int = 200")
    val (tm2, snaps2) = run(modNew, snaps1)

    assert(snaps2.length == 3)
    assert(snaps1(0).typedSection eq snaps2(0).typedSection,
      "Section 1 should not be re-typed")
    assert(!(snaps1(1).typedSection eq snaps2(1).typedSection),
      "Section 2 should have been re-typed")
    assert(!(snaps1(2).typedSection eq snaps2(2).typedSection),
      "Section 3 should have been re-typed")
    assert(!tm2.hasErrors, s"unexpected errors: ${tm2.errors}")

  // ─── Test: section hash stability ───────────────────────────────────────────

  test("hashSection is stable for the same content"):
    val mod = threeSection()
    val h1 = SectionSnapshot.hashSection(mod.sections.head)
    val h2 = SectionSnapshot.hashSection(mod.sections.head)
    assert(h1 == h2, "Hash must be deterministic")
    assert(h1.length == 64, "SHA-256 hex digest must be 64 characters")

  test("hashSection differs for different content"):
    val mod1 = threeSection(body1 = "val x: Int = 1")
    val mod2 = threeSection(body1 = "val x: Int = 2")
    val h1 = SectionSnapshot.hashSection(mod1.sections.head)
    val h2 = SectionSnapshot.hashSection(mod2.sections.head)
    assert(h1 != h2, "Different content must produce different hashes")

  // ─── Test: type aliases propagate across sections correctly ─────────────────

  test("type alias defined in section 1 is visible in section 2 after incremental re-run"):
    val mod = Parser.parse(
      """# Aliases
        |
        |```scalascript
        |type MyInt = Int
        |```
        |
        |# Users
        |
        |```scalascript
        |val n: MyInt = 42
        |```
        |""".stripMargin
    )
    val (tm1, snaps1) = run(mod)
    assert(!tm1.hasErrors, s"Run 1 errors: ${tm1.errors}")

    // Re-run with no changes — section 2 must still compile correctly.
    val (tm2, _) = run(mod, snaps1)
    assert(!tm2.hasErrors, s"Run 2 (incremental, no changes) errors: ${tm2.errors}")

  // ─── Test: snapshot typeAliases/opaqueTypes restoration ─────────────────────

  test("snapshot captures typeAliases accumulated up to each section"):
    val mod = Parser.parse(
      """# A
        |
        |```scalascript
        |type UserId = String
        |```
        |
        |# B
        |
        |```scalascript
        |type OrderId = Int
        |```
        |""".stripMargin
    )
    val (_, snaps) = run(mod)
    assert(snaps.length == 2)
    // After section A, only UserId should be registered.
    assert(snaps(0).typeAliases.contains("UserId"),
      "Snapshot 0 should have UserId alias")
    assert(!snaps(0).typeAliases.contains("OrderId"),
      "Snapshot 0 should not yet have OrderId")
    // After section B, both should be present.
    assert(snaps(1).typeAliases.contains("UserId"),
      "Snapshot 1 should retain UserId alias")
    assert(snaps(1).typeAliases.contains("OrderId"),
      "Snapshot 1 should have OrderId alias")

  // ─── Tests: error persistence across incremental re-runs ────────────────────
  //
  // Key invariant: type errors from unchanged sections must appear in
  // TypedModule.errors after every incremental re-run.  Previously, changing
  // a later section would silently drop errors from earlier unchanged sections
  // because the errors buffer was cleared and only re-typed sections added back.

  private def modWithTypeError(body2: String = "val y: Int = 1"): scalascript.ast.Module =
    Parser.parse(
      s"""# Section1
         |
         |```scalascript
         |val x: String = 42
         |```
         |
         |# Section2
         |
         |```scalascript
         |$body2
         |```
         |""".stripMargin
    )

  test("snapshot stores per-section errors"):
    val mod = modWithTypeError()
    val (tm, snaps) = run(mod)
    assert(tm.hasErrors, "Section1 has a type error")
    assert(snaps(0).errors.nonEmpty, "snapshot(0) must store Section1's error")
    assert(snaps(1).errors.isEmpty,  "snapshot(1) should have no errors (Section2 is clean)")

  test("errors from unchanged section 1 persist when section 2 changes"):
    val mod1 = modWithTypeError("val y: Int = 1")
    val (tm1, snaps1) = run(mod1)
    assert(tm1.hasErrors, "Run 1: Section1 has a type error")

    // Mutate section 2 content — section 1 is unchanged.
    val mod2 = modWithTypeError("val y: Int = 2")
    val (tm2, _) = run(mod2, snaps1)
    assert(tm2.hasErrors,
      "Run 2: Section1's type error must still appear even though Section2 changed")
    val msgs = tm2.errors.map(_.msg)
    assert(msgs.exists(m => m.toLowerCase.contains("string") || m.toLowerCase.contains("int")),
      s"Expected a String/Int mismatch error, got: $msgs")

  test("error count is stable across unchanged incremental runs"):
    val mod = modWithTypeError()
    val (tm1, snaps1) = run(mod)
    val errorCount1 = tm1.errors.length

    // Three no-change re-runs — error count must stay the same.
    val (tm2, snaps2) = run(mod, snaps1)
    assert(tm2.errors.length == errorCount1, "Run 2: error count must be stable")
    val (tm3, snaps3) = run(mod, snaps2)
    assert(tm3.errors.length == errorCount1, "Run 3: error count must be stable")
    val (tm4, _)      = run(mod, snaps3)
    assert(tm4.errors.length == errorCount1, "Run 4: error count must be stable")

  test("fixing an error in section 1 removes it even when section 2 is unchanged"):
    val modWithError = Parser.parse(
      """# Section1
        |
        |```scalascript
        |val x: String = 42
        |```
        |
        |# Section2
        |
        |```scalascript
        |val y: Int = 99
        |```
        |""".stripMargin
    )
    val (tm1, snaps1) = run(modWithError)
    assert(tm1.hasErrors, "Run 1: should have type error")

    // Fix the error in section 1 — section 2 is identical.
    val modFixed = Parser.parse(
      """# Section1
        |
        |```scalascript
        |val x: String = "hello"
        |```
        |
        |# Section2
        |
        |```scalascript
        |val y: Int = 99
        |```
        |""".stripMargin
    )
    val (tm2, _) = run(modFixed, snaps1)
    assert(!tm2.hasErrors, s"Run 2: error should be gone after fix, got: ${tm2.errors}")

  test("errors from both unchanged and re-typed sections accumulate correctly"):
    val mod1 = Parser.parse(
      """# A
        |
        |```scalascript
        |val a: String = 1
        |```
        |
        |# B
        |
        |```scalascript
        |val b: Int = 2
        |```
        |
        |# C
        |
        |```scalascript
        |val c: Boolean = "wrong"
        |```
        |""".stripMargin
    )
    val (tm1, snaps1) = run(mod1)
    // A has 1 error, B is clean, C has 1 error.
    assert(tm1.errors.length == 2, s"Run 1: expected 2 errors, got: ${tm1.errors}")

    // Change B only — A and C are unchanged.
    val mod2 = Parser.parse(
      """# A
        |
        |```scalascript
        |val a: String = 1
        |```
        |
        |# B
        |
        |```scalascript
        |val b: Int = 99
        |```
        |
        |# C
        |
        |```scalascript
        |val c: Boolean = "wrong"
        |```
        |""".stripMargin
    )
    val (tm2, _) = run(mod2, snaps1)
    assert(tm2.errors.length == 2,
      s"Run 2: errors from unchanged A and C must both appear, got: ${tm2.errors}")
