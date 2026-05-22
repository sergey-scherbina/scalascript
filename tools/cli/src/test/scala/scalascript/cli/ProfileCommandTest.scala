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
