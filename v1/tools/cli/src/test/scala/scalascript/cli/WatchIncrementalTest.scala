package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Verifies that [[Interpreter.runWithCheckpoints]] and
 *  [[Interpreter.runSectionsIncremental]] together yield the same observable
 *  output as a full [[Interpreter.run]] while skipping unchanged sections. */
class WatchIncrementalTest extends AnyFunSuite:

  private def makeInterp(buf: java.io.ByteArrayOutputStream) =
    Interpreter(new java.io.PrintStream(buf, true, "UTF-8"))

  private def src(sections: String*): String =
    sections.mkString("\n\n")

  private val sec1 = "# Sec1\n```scala\ndef greet(n: String) = s\"Hello $n\"\n```"
  private val sec2 = "# Sec2\n```scala\nprintln(greet(\"world\"))\n```"
  private val sec3 = "# Sec3\n```scala\nprintln(greet(\"ssc\"))\n```"

  test("runWithCheckpoints produces same output as run"):
    val buf1, buf2 = new java.io.ByteArrayOutputStream()
    val mod = Parser.parse(src(sec1, sec2, sec3))
    makeInterp(buf1).run(mod)
    makeInterp(buf2).runWithCheckpoints(mod)
    assert(buf1.toString("UTF-8") == buf2.toString("UTF-8"))

  test("runWithCheckpoints returns length sections+1 checkpoints"):
    val mod = Parser.parse(src(sec1, sec2, sec3))
    val buf = new java.io.ByteArrayOutputStream()
    val cps = makeInterp(buf).runWithCheckpoints(mod)
    assert(cps.length == mod.sections.length + 1,
      s"expected ${mod.sections.length + 1}, got ${cps.length}")

  test("runSectionsIncremental skips unchanged prefix"):
    val buf = new java.io.ByteArrayOutputStream()
    val interp = makeInterp(buf)
    val mod1 = Parser.parse(src(sec1, sec2, sec3))
    val cps1 = interp.runWithCheckpoints(mod1)
    buf.reset()

    // Change only sec2; sec1 should be reused from checkpoint
    val sec2b = "# Sec2\n```scala\nprintln(greet(\"changed\"))\n```"
    val mod2  = Parser.parse(src(sec1, sec2b, sec3))
    interp.runSectionsIncremental(mod2.sections, firstChanged = 1, prevCheckpoints = cps1)
    val out = buf.toString("UTF-8").trim
    // sec1 skipped (greet still defined), sec2+sec3 re-run
    assert(out.contains("changed"), s"expected 'changed' in: $out")
    assert(out.contains("ssc"),     s"expected 'ssc' in: $out")

  test("runSectionsIncremental with firstChanged=0 re-runs everything"):
    val buf = new java.io.ByteArrayOutputStream()
    val interp = makeInterp(buf)
    val mod1 = Parser.parse(src(sec1, sec2))
    val cps1 = interp.runWithCheckpoints(mod1)
    buf.reset()

    val sec1b = "# Sec1\n```scala\ndef greet(n: String) = s\"Hi $n\"\n```"
    val mod2  = Parser.parse(src(sec1b, sec2))
    interp.runSectionsIncremental(mod2.sections, firstChanged = 0, prevCheckpoints = cps1)
    val out = buf.toString("UTF-8").trim
    assert(out.contains("Hi world"), s"expected 'Hi world' in: $out")

  test("incremental result matches full re-run output"):
    val bufFull, bufIncr = new java.io.ByteArrayOutputStream()
    val sec2b = "# Sec2\n```scala\nprintln(greet(\"everyone\"))\n```"
    val mod   = Parser.parse(src(sec1, sec2b, sec3))

    // Full re-run on fresh interpreter
    makeInterp(bufFull).run(mod)

    // Incremental: base on sec1+sec2+sec3, then apply sec2b change
    val interpI = makeInterp(bufIncr)
    val modBase = Parser.parse(src(sec1, sec2, sec3))
    val cps     = interpI.runWithCheckpoints(modBase)
    bufIncr.reset()
    interpI.runSectionsIncremental(mod.sections, firstChanged = 1, prevCheckpoints = cps)

    assert(bufFull.toString("UTF-8") == bufIncr.toString("UTF-8"),
      s"full='${bufFull.toString("UTF-8")}' incr='${bufIncr.toString("UTF-8")}'")
