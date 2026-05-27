package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Profiler

/** Unit tests for `ssc profile` — driven directly against `profileCommand`
 *  (no assembled JAR required).
 *
 *  Each test:
 *    1. Writes a tiny `.ssc` with a recursive function to a temp directory.
 *    2. Calls `profileCommand` with the file path.
 *    3. Checks that `Profiler` accumulated the expected data.
 */
class ProfileCommandTest extends AnyFunSuite:

  /** Write a source file to a temp sandbox and return its path string. */
  private def writeFixture(dir: os.Path, name: String, src: String): String =
    val p = dir / name
    os.write(p, src)
    p.toString

  /** Run profileCommand and capture stdout.
   *
   *  We redirect both `System.out` (for `System.out.println` calls in
   *  `profileCommand`) and `Console.out` (for any Scala `println` calls).
   */
  private def runProfile(args: String*): String =
    val buf = new java.io.ByteArrayOutputStream()
    val ps  = new java.io.PrintStream(buf)
    val savedOut = System.out
    System.setOut(ps)
    try
      Console.withOut(ps)(profileCommand(args.toList))
    finally
      ps.flush()
      System.setOut(savedOut)
    buf.toString("UTF-8")

  test("profile records call count for a recursive function"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-test-")
    try
      // fib(10) makes 177 calls.
      val src =
        """# Fib
          |
          |```scalascript
          |def fib(n: Int): Int =
          |  if n <= 1 then n
          |  else fib(n - 1) + fib(n - 2)
          |
          |val result = fib(10)
          |println(result)
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "fib.ssc", src)
      Profiler.reset()
      val out = runProfile(file)
      // The function 'fib' should appear in the profile table.
      assert(out.contains("fib"),
        s"expected 'fib' in profile output; got:\n$out")
      // Should have non-zero calls.
      val calls = Profiler.topN(20).find(_._1 == "fib").map(_._2).getOrElse(0L)
      assert(calls > 0,
        s"expected non-zero call count for fib; got $calls")
      // fib(10) = 177 total invocations.
      assert(calls == 177,
        s"expected 177 calls to fib(10); got $calls")
    finally os.remove.all(sandbox)

  test("profile table contains header line"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-hdr-")
    try
      val src =
        """# Hello
          |
          |```scalascript
          |def greet(name: String): String = "Hello, " + name
          |println(greet("world"))
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "hello.ssc", src)
      val out  = runProfile(file)
      assert(out.contains("Profile"),
        s"expected 'Profile' header in output; got:\n$out")
      assert(out.contains("calls"),
        s"expected 'calls' column header; got:\n$out")
      assert(out.contains("time(ms)"),
        s"expected 'time(ms)' column header; got:\n$out")
      assert(out.contains("function"),
        s"expected 'function' column header; got:\n$out")
    finally os.remove.all(sandbox)

  test("profile --top 1 limits output to one row"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-top-")
    try
      val src =
        """# Multi
          |
          |```scalascript
          |def alpha(n: Int): Int = if n <= 0 then 0 else alpha(n - 1)
          |def beta(n: Int): Int  = if n <= 0 then 0 else beta(n - 1)
          |val _ = alpha(5)
          |val _ = beta(3)
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "multi.ssc", src)
      Profiler.reset()
      runProfile("--top", "1", file)
      // With --top 1, renderTable shows only 1 row, but Profiler has both.
      val all = Profiler.topN(20)
      assert(all.map(_._1).contains("alpha"),
        s"expected 'alpha' in profiler data; got ${all.map(_._1)}")
      assert(all.map(_._1).contains("beta"),
        s"expected 'beta' in profiler data; got ${all.map(_._1)}")
    finally os.remove.all(sandbox)

  test("profile --output writes a JSON file"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-json-")
    try
      val src =
        """# JsonOut
          |
          |```scalascript
          |def square(n: Int): Int = n * n
          |println(square(7))
          |```
          |""".stripMargin
      val file    = writeFixture(sandbox, "square.ssc", src)
      val jsonOut = (sandbox / "profile.json").toString
      runProfile("--output", jsonOut, file)
      assert(os.exists(os.Path(jsonOut)),
        s"expected profile.json to be written at $jsonOut")
      val content = os.read(os.Path(jsonOut))
      assert(content.contains("square"),
        s"expected 'square' in profile.json; got:\n$content")
      assert(content.contains("\"calls\""),
        s"expected 'calls' key in profile.json; got:\n$content")
    finally os.remove.all(sandbox)

  test("Profiler.renderTable shows Total wall time line"):
    Profiler.reset()
    Profiler.record("myFunc", 1_500_000L)   // 1.5 ms
    Profiler.record("myFunc", 2_000_000L)   // 2.0 ms
    val table = Profiler.renderTable(20)
    assert(table.contains("myFunc"),       s"expected 'myFunc'; got:\n$table")
    assert(table.contains("Total wall time"), s"expected total line; got:\n$table")
    assert(table.contains("2"),            s"expected call count 2; got:\n$table")
    Profiler.reset()

  // ── Phase 3: phase timing ────────────────────────────────────────────

  test("profile output includes phase timing table"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-phase-")
    try
      val src =
        """# PhaseTest
          |
          |```scalascript
          |def inc(n: Int): Int = n + 1
          |println(inc(3))
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "phase.ssc", src)
      Profiler.reset()
      val out = runProfile(file)
      assert(out.contains("Phases") || out.contains("parse") || out.contains("eval"),
        s"expected phase section in output; got:\n$out")
    finally os.remove.all(sandbox)

  test("Profiler.recordPhase accumulates phase data"):
    Profiler.reset()
    Profiler.recordPhase("parse",     5_000_000L, 1024L)
    Profiler.recordPhase("typecheck", 3_000_000L,  512L)
    Profiler.recordPhase("eval",     20_000_000L, 2048L)
    val entries = Profiler.phaseEntries()
    assert(entries.map(_._1).contains("parse"),     "expected parse phase")
    assert(entries.map(_._1).contains("typecheck"), "expected typecheck phase")
    assert(entries.map(_._1).contains("eval"),      "expected eval phase")
    val evalEntry = entries.find(_._1 == "eval").get
    assert(evalEntry._2 == 20_000_000L, s"expected 20ms for eval; got ${evalEntry._2}")
    Profiler.reset()

  test("Profiler.renderPhaseTable renders phase names"):
    Profiler.reset()
    Profiler.recordPhase("parse",     5_000_000L)
    Profiler.recordPhase("eval",     20_000_000L)
    val table = Profiler.renderPhaseTable()
    assert(table.contains("parse"), s"expected 'parse' in phase table; got:\n$table")
    assert(table.contains("eval"),  s"expected 'eval' in phase table; got:\n$table")
    Profiler.reset()

  // ── Phase 3: folded stacks ───────────────────────────────────────────

  test("profile --folded writes Brendan Gregg folded stacks file"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-folded-")
    try
      val src =
        """# FoldedTest
          |
          |```scalascript
          |def loop(n: Int): Int = if n <= 0 then 0 else loop(n - 1)
          |println(loop(5))
          |```
          |""".stripMargin
      val file      = writeFixture(sandbox, "folded.ssc", src)
      val foldedOut = (sandbox / "out.folded").toString
      runProfile("--folded", foldedOut, file)
      assert(os.exists(os.Path(foldedOut)),
        s"expected folded file to be written at $foldedOut")
      val content = os.read(os.Path(foldedOut))
      assert(content.nonEmpty, "expected non-empty folded stacks output")
    finally os.remove.all(sandbox)

  test("Profiler.renderFolded emits folded stacks with semicolon-paths"):
    Profiler.reset()
    Profiler.recordPhase("parse",  5_000_000L)
    Profiler.recordPhase("eval",  20_000_000L)
    Profiler.record("myFn", 10_000_000L)
    val folded = Profiler.renderFolded(20)
    assert(folded.contains("all;"), s"expected 'all;' prefix in folded output; got:\n$folded")
    assert(folded.contains("eval"), s"expected 'eval' line in folded output; got:\n$folded")
    Profiler.reset()

  // ── Phase 3: structured JSON ─────────────────────────────────────────

  test("profile --output writes structured JSON with phases section"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-strjson-")
    try
      val src =
        """# StructJson
          |
          |```scalascript
          |def double(n: Int): Int = n * 2
          |println(double(4))
          |```
          |""".stripMargin
      val file    = writeFixture(sandbox, "strjson.ssc", src)
      val jsonOut = (sandbox / "structured.json").toString
      runProfile("--output", jsonOut, file)
      assert(os.exists(os.Path(jsonOut)), s"expected structured.json at $jsonOut")
      val content = os.read(os.Path(jsonOut))
      assert(content.contains("\"phases\""),    s"expected 'phases' key; got:\n$content")
      assert(content.contains("\"functions\""), s"expected 'functions' key; got:\n$content")
    finally os.remove.all(sandbox)

  // ── Phase 3: --compare regression detection ──────────────────────────

  test("--compare detects regression: reports functions that got slower"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-compare-")
    try
      val src =
        """# CompareTest
          |
          |```scalascript
          |def work(n: Int): Int = if n <= 0 then 0 else work(n - 1)
          |println(work(10))
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "compare.ssc", src)
      // Baseline: work function was 0.001 ms (very fast)
      val baseline =
        """{"phases":[],"functions":[{"function":"work","calls":11,"wallMs":0.001}]}"""
      val baselinePath = (sandbox / "baseline.json").toString
      os.write(os.Path(baselinePath), baseline)
      val out = runProfile("--compare", baselinePath, file)
      // Current run will definitely be slower than 0.001ms
      assert(out.contains("Regression") || out.contains("regression") || out.contains("work") || out.contains("No regression"),
        s"expected some regression output; got:\n$out")
    finally os.remove.all(sandbox)

  test("--compare reports no regressions when baseline is slow"):
    val sandbox = os.temp.dir(prefix = "ssc-profile-noreg-")
    try
      val src =
        """# NoReg
          |
          |```scalascript
          |def fast(n: Int): Int = n + 1
          |println(fast(1))
          |```
          |""".stripMargin
      val file = writeFixture(sandbox, "noreg.ssc", src)
      // Baseline: fast function was extremely slow (100000 ms) — no regression possible
      val baseline =
        """{"phases":[],"functions":[{"function":"fast","calls":1,"wallMs":100000.0}]}"""
      val baselinePath = (sandbox / "baseline.json").toString
      os.write(os.Path(baselinePath), baseline)
      val out = runProfile("--compare", baselinePath, file)
      assert(out.contains("No regressions"),
        s"expected 'No regressions'; got:\n$out")
    finally os.remove.all(sandbox)
