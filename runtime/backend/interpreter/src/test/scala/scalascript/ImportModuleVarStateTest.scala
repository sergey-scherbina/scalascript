package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Regression for the cross-module mutable module-`var` bug (interp-module-var-home, 2026-06-15).
 *
 *  An imported module that declares a top-level `var` (a shared registry) must have its exported
 *  functions read AND write that var's LIVE state — regardless of which module calls them. The bug:
 *  imported functions were bound to an import-time SNAPSHOT of the child globals (frozen) and ran in
 *  the CALLER's interpreter, so `var` reassignments went to the caller's globals and reads stayed at
 *  the initial value → `putAndSize` returned 1,1,1 instead of 1,2,3. The fix binds a var-holding
 *  module's exported functions to run in the defining (child) interpreter, where the module's globals
 *  are live. Pure/effectful modules with no top-level var are unaffected (still snapshot-bound). */
class ImportModuleVarStateTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString

  test("imported module-level var is shared, live, and consistent across import boundaries") {
    val dir = os.temp.dir(prefix = "ssc-module-var-")
    os.write(dir / "registry.ssc",
      """---
        |name: registry
        |exports:
        |  - putAndSize
        |  - getAllSize
        |  - sizeViaInternal
        |---
        |```scalascript
        |var store: Map[String, String] = Map()
        |def putAndSize(k: String, v: String): Int =
        |  store = store + (k -> v)
        |  store.size
        |def getAllSize(): Int = store.size
        |def sizeViaInternal(): Int = getAllSize()
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """---
        |name: main
        |---
        |[putAndSize, getAllSize, sizeViaInternal](registry.ssc)
        |```scalascript
        |println("puts=" + putAndSize("x","1").toString + "," + putAndSize("y","2").toString + "," + putAndSize("z","3").toString)
        |println("cross=" + getAllSize().toString)
        |println("intra=" + sizeViaInternal().toString)
        |```
        |""".stripMargin)
    val out = run(dir, "main.ssc")
    // each put grows the SHARED store (1,2,3); both cross-module and intra-module reads see all 3.
    assert(out.contains("puts=1,2,3"), s"mutations not accumulating in the shared module var:\n$out")
    assert(out.contains("cross=3"),    s"cross-module read of the module var is stale:\n$out")
    assert(out.contains("intra=3"),    s"intra-module read of the module var is stale:\n$out")
  }
