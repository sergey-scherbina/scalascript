package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** v1.64 — @deprecated / @experimental annotation warnings (arch-lib-p1).
 *
 *  Library authors mark APIs with `@deprecated` or `@experimental`; the typer
 *  emits a warning at every call site.  `--fatal-warnings` turns those warnings
 *  into hard errors so CI can enforce clean-up.
 */
class TyperAnnotationWarningsTest extends AnyFunSuite:

  private def moduleOf(src: String): scalascript.ast.Module =
    Parser.parse(
      s"""# Test
         |
         |```scalascript
         |$src
         |```
         |""".stripMargin
    )

  // ─── @deprecated ──────────────────────────────────────────────────────────

  test("@deprecated def — call site emits a warning"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated
        |def oldFn(): String = "old"
        |def caller(): String = oldFn()
        |""".stripMargin
    ))
    assert(!tm.hasErrors, s"no errors expected; got: ${tm.errors.map(_.show).mkString(", ")}")
    val warns = tm.warnings
    assert(warns.nonEmpty, "expected a deprecation warning at the call site")
    assert(warns.exists(_.msg.contains("oldFn")),
      s"warning should mention the deprecated name; got: ${warns.map(_.msg).mkString(" | ")}")

  test("@deprecated with message — warning includes the message"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated("use newFn instead")
        |def oldFn(): String = "old"
        |def caller(): String = oldFn()
        |""".stripMargin
    ))
    val warns = tm.warnings
    assert(warns.exists(_.msg.contains("use newFn instead")),
      s"warning should include the deprecation message; got: ${warns.map(_.msg).mkString(" | ")}")

  test("@deprecated with since parameter — warning mentions since"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated("old API", since = "1.0")
        |def legacyFn(): Int = 42
        |def caller(): Int = legacyFn()
        |""".stripMargin
    ))
    val warns = tm.warnings
    assert(warns.exists(w => w.msg.contains("legacyFn") && w.msg.contains("1.0")),
      s"warning should include 'since 1.0'; got: ${warns.map(_.msg).mkString(" | ")}")

  test("@deprecated def — no warning when not called"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated
        |def unusedOld(): String = "old"
        |def caller(): String = "fresh"
        |""".stripMargin
    ))
    assert(tm.warnings.isEmpty,
      s"no warning expected when deprecated def is never called; got: ${tm.warnings.map(_.msg).mkString(", ")}")

  test("@deprecated def — multiple call sites each produce a warning"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated("please migrate")
        |def oldOp(): Int = 0
        |def a(): Int = oldOp()
        |def b(): Int = oldOp()
        |""".stripMargin
    ))
    val warns = tm.warnings.filter(_.msg.contains("oldOp"))
    assert(warns.length >= 2,
      s"expected at least 2 deprecation warnings (one per call site); got ${warns.length}")

  // ─── @experimental ────────────────────────────────────────────────────────

  test("@experimental def — call site emits a warning"):
    val tm = Typer.typeCheck(moduleOf(
      """@experimental
        |def betaFn(): Boolean = true
        |def caller(): Boolean = betaFn()
        |""".stripMargin
    ))
    assert(!tm.hasErrors)
    val warns = tm.warnings
    assert(warns.exists(_.msg.contains("betaFn")),
      s"expected an experimental warning; got: ${warns.map(_.msg).mkString(" | ")}")

  test("@experimental with notice — warning includes the notice"):
    val tm = Typer.typeCheck(moduleOf(
      """@experimental("subject to change without notice")
        |def unstableFn(): String = "?"
        |def caller(): String = unstableFn()
        |""".stripMargin
    ))
    val warns = tm.warnings
    assert(warns.exists(_.msg.contains("subject to change without notice")),
      s"experimental notice not in warning; got: ${warns.map(_.msg).mkString(" | ")}")

  // ─── Non-annotated — no false positives ───────────────────────────────────

  test("non-annotated def — no spurious warnings"):
    val tm = Typer.typeCheck(moduleOf(
      """def stableFn(): Int = 1
        |def caller(): Int = stableFn()
        |""".stripMargin
    ))
    assert(tm.warnings.isEmpty,
      s"no warnings expected for non-annotated def; got: ${tm.warnings.map(_.msg).mkString(", ")}")

  // ─── fatalWarnings mode ───────────────────────────────────────────────────

  test("fatalWarnings — @deprecated call becomes a hard error"):
    val tm = Typer.typeCheckFatalWarnings(moduleOf(
      """@deprecated
        |def oldFn(): String = "x"
        |def caller(): String = oldFn()
        |""".stripMargin
    ))
    assert(tm.hasErrors,
      "fatalWarnings mode should turn deprecated warning into an error")
    assert(tm.errors.exists(_.msg.contains("oldFn")),
      s"error should name oldFn; got: ${tm.errors.map(_.msg).mkString(" | ")}")

  test("fatalWarnings — @experimental call becomes a hard error"):
    val tm = Typer.typeCheckFatalWarnings(moduleOf(
      """@experimental
        |def betaFn(): Int = 0
        |def user(): Int = betaFn()
        |""".stripMargin
    ))
    assert(tm.hasErrors,
      "fatalWarnings mode should turn experimental warning into an error")

  test("warnings — TypedModule.warnings returns only non-error entries"):
    val tm = Typer.typeCheck(moduleOf(
      """@deprecated
        |def oldFn(): String = "x"
        |def caller(): String = oldFn()
        |""".stripMargin
    ))
    assert(!tm.hasErrors, "deprecated call is a warning, not an error")
    assert(tm.warnings.forall(_.isWarning),
      "all entries from TypedModule.warnings should have isWarning = true")
