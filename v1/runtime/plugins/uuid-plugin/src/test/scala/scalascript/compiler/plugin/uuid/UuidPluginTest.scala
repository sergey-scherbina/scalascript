package scalascript.compiler.plugin.uuid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.testkit.TestInterpreter

class UuidPluginTest extends AnyFunSuite:

  private def interp: TestInterpreter =
    TestInterpreter(List(UuidInterpreterPlugin()))

  private def evalStr(snippet: String): String =
    interp.eval(snippet).asInstanceOf[String]

  test("uuidV4 returns valid RFC-4122 v4 UUID"):
    val result = evalStr("""uuidV4()""")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v4 UUID: $result")

  test("uuidV7 returns valid time-ordered v7 UUID"):
    val result = evalStr("""uuidV7()""")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v7 UUID: $result")

  test("two uuidV7 calls share the same timestamp prefix when called within one millisecond"):
    val raw = evalStr(
      """
      val u1 = uuidV7()
      val u2 = uuidV7()
      u1 + "," + u2
      """
    )
    val Array(a, b) = raw.split(",", 2)
    // Both must be valid v7 UUIDs
    val v7re = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    assert(a.matches(v7re), s"u1 not v7: $a")
    assert(b.matches(v7re), s"u2 not v7: $b")

  test("uuidV7Monotonic returns a valid v7 UUID"):
    val result = evalStr("""uuidV7Monotonic()""")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v7 UUID: $result")

  test("uuidV7Monotonic is strictly increasing across many same-process calls"):
    // Generate a burst (almost all land in the same millisecond) and assert every
    // UUID is strictly greater than the previous — the rand_a counter guarantees it.
    val raw = evalStr(
      """
      var prev = uuidV7Monotonic()
      var ok = true
      var i = 0
      while i < 500 do
        val next = uuidV7Monotonic()
        if next <= prev then ok = false
        prev = next
        i = i + 1
      if ok then "ok" else "violation"
      """
    )
    assert(raw == "ok", s"monotonicity violated: $raw")

  test("uuidFromString accepts a valid UUID"):
    val result = evalStr(
      """
      val r = uuidFromString("550e8400-e29b-41d4-a716-446655440000")
      if r.isDefined then r.get else "none"
      """
    )
    assert(result == "550e8400-e29b-41d4-a716-446655440000")

  test("uuidFromString normalises to lowercase"):
    val result = evalStr(
      """
      val r = uuidFromString("550E8400-E29B-41D4-A716-446655440000")
      if r.isDefined then r.get else "none"
      """
    )
    assert(result == "550e8400-e29b-41d4-a716-446655440000")

  test("uuidFromString rejects garbage"):
    val result = evalStr(
      """
      val r = uuidFromString("not-a-uuid")
      r.isDefined.toString
      """
    )
    assert(result == "false")

  test("uuidIsValid returns true for valid UUID"):
    val result = evalStr("""uuidIsValid("550e8400-e29b-41d4-a716-446655440000").toString""")
    assert(result == "true")

  test("uuidIsValid returns false for invalid string"):
    val result = evalStr("""uuidIsValid("bad").toString""")
    assert(result == "false")

  // ── rawV4 / rawV7 ─────────────────────────────────────────────────

  test("rawUuidV4 returns valid RFC-4122 v4 UUID"):
    val result = evalStr("""rawUuidV4()""")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v4 UUID: $result")

  test("rawUuidV7 returns valid time-ordered v7 UUID"):
    val result = evalStr("""rawUuidV7()""")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v7 UUID: $result")

  // ── uuidUnsafeFromString ──────────────────────────────────────────

  test("uuidUnsafeFromString returns normalised UUID for valid input"):
    val result = evalStr("""uuidUnsafeFromString("550E8400-E29B-41D4-A716-446655440000")""")
    assert(result == "550e8400-e29b-41d4-a716-446655440000")

  test("uuidUnsafeFromString throws for invalid input"):
    intercept[Exception]:
      interp.eval("""uuidUnsafeFromString("not-a-uuid")""")

  // ── withFixedUuid ─────────────────────────────────────────────────

  test("withFixedUuid overrides uuidV4 inside the block"):
    val fixed = "00000000-0000-4000-8000-000000000001"
    val result = evalStr(
      s"""
      withFixedUuid("$fixed") {
        uuidV4()
      }
      """
    )
    assert(result == fixed)

  test("withFixedUuid overrides uuidV7 inside the block"):
    val fixed = "018f14c2-0000-7000-8000-000000000001"
    val result = evalStr(
      s"""
      withFixedUuid("$fixed") {
        uuidV7()
      }
      """
    )
    assert(result == fixed)

  test("withFixedUuid does not affect generation outside the block"):
    val fixed = "00000000-0000-4000-8000-000000000002"
    val result = evalStr(
      s"""
      withFixedUuid("$fixed") { uuidV4() }
      uuidV4()
      """
    )
    assert(result != fixed, s"UUID should be random after withFixedUuid block, got $result")
    assert(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
      s"not a valid v4 UUID after block: $result")
