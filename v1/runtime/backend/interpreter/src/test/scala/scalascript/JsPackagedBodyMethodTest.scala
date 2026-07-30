package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** A case class's BODY methods — `override def toString`, a trait method implemented on the class —
 *  must be compiled on BOTH of JsGen's emission paths: the top-level one, and the packaged one taken
 *  by any module whose front-matter declares `package:`.
 *
 *  This is the gate the fix did not ship with. `4b92ac864` corrected the packaged path (the shared
 *  helper `caseClassBodyMethodRegistrations`) after `dsl-mini-language` printed
 *  `PassError(name-resolve, undefined variable: z, <unknown>, 0, 0)` on js where int and v2 print
 *  `[name-resolve] undefined variable: z`. Nothing was left watching it, and the only thing that had
 *  ever noticed was that one corpus row — which `contract.sc` had been SKIPping (its int lane was
 *  non-runnable), so it surfaced by accident rather than by design. A fix whose only witness is a
 *  skipped row is one refactor away from silently returning.
 *
 *  The discriminator, measured by bisecting `std/dsl/passes.ssc` down to a single line: the
 *  front-matter `package:` declaration. Remove that one line from the module and the override is
 *  honoured; keep it and the js lane prints the raw record. A LOCAL case class was always fine, which
 *  is exactly what kept the defect hidden — so a test that only exercises one path proves nothing
 *  about the other, and this one asserts both in the same shape.
 */
class JsPackagedBodyMethodTest extends AnyFunSuite:

  private val caseClassWithToString =
    """case class PassError(
      |  phase:   String,
      |  message: String,
      |  source:  String = "<unknown>",
      |  line:    Int    = 0,
      |  col:     Int    = 0
      |):
      |  override def toString: String =
      |    if line == 0 then s"[$phase] $message"
      |    else s"[$phase] $source:$line:$col — $message"
      |
      |def mk(): PassError = PassError("phase-x", "message-y")""".stripMargin

  private def topLevelModule: String =
    s"# T\n```scalascript\n$caseClassWithToString\n```\n"

  /** The same code in a module that declares `package:` — the path that had no body-method
    * compilation at all. `exports:` is present because a real `package:` module carries one; the
    * defect did not depend on it, but the fixture should look like the thing it stands for. */
  private def packagedModule: String =
    s"""---
       |name: t-packaged
       |version: 1.0.0
       |package: std.dsl.gatefx
       |exports:
       |  - PassError
       |  - mk
       |---
       |
       |# T
       |
       |```scalascript
       |$caseClassWithToString
       |```
       |""".stripMargin

  /** `_registerExt('<name>', …, '<Type>')` is how `_dispatch` finds a body method at runtime, so its
    * presence is the observable that distinguishes "compiled" from "silently dropped". Asserting on
    * the emitted registration rather than on program output keeps the test a codegen test — it fails
    * for one reason and names it. */
  private def registersToString(js: String): Boolean =
    js.contains("_registerExt('toString'") && js.contains("'PassError')")

  test("a top-level case class's toString override is compiled"):
    val js = JsGen.generate(Parser.parse(topLevelModule))
    assert(registersToString(js),
      s"no _registerExt for PassError.toString on the TOP-LEVEL path:\n${bodyMethodLines(js)}")

  test("a `package:` module's case class toString override is compiled too"):
    val js = JsGen.generate(Parser.parse(packagedModule))
    assert(registersToString(js),
      "no _registerExt for PassError.toString on the PACKAGED path — this is the 4b92ac864 defect " +
      s"returning: a `package:` module loses its body methods silently.\n${bodyMethodLines(js)}")

  /** Both paths, one assertion: the defect was not "toString is broken", it was "one of two paths
    * compiles it". A pair of independent tests can drift into only one being maintained; this states
    * the invariant that made the bug possible. */
  test("both emission paths agree about body-method compilation"):
    val top      = JsGen.generate(Parser.parse(topLevelModule))
    val packaged = JsGen.generate(Parser.parse(packagedModule))
    assert(registersToString(top) == registersToString(packaged),
      s"the two paths disagree — top-level=${registersToString(top)} " +
      s"packaged=${registersToString(packaged)}. Half an emitter is how this bug happened.")

  private def bodyMethodLines(js: String): String =
    val hits = js.linesIterator.filter(_.contains("_registerExt")).toList
    if hits.isEmpty then "  (no _registerExt lines at all in the emitted JS)"
    else hits.map("  " + _).mkString("\n")
