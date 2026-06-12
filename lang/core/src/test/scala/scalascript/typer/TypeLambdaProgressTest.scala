package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.InterfaceScope
import scalascript.parser.Parser

/** PROGRESS TRACKER for type-level lambdas (sprint `type-lambda-p1/p2/p3`).
 *
 *  Type lambdas are surface-only in an interpreter-first language (types are
 *  erased at runtime), so there is nothing to *micro-benchmark* — this suite is
 *  the capability tracker the bench request asked for: it pins what works today
 *  and marks the target end-state with `pending`, so the diff between "passing"
 *  and "pending" IS the progress dashboard.
 *
 *  Decision (2026-06-12): support BOTH surfaces, equivalent —
 *    - placeholder/wildcard short form:  `Map[Int, _]`   (each `_` = a fresh
 *      lambda param, bound left→right in source order)
 *    - Scala-3 native:                   `[X] =>> Map[Int, X]`
 *  `_` desugars to `=>>`. Canonical `show` = `=>>`.
 *
 *  As `type-lambda-p2` lands: replace the matching `pending` body with the real
 *  assertion. The "documents current behaviour" tests then update to the new
 *  reality. */
class TypeLambdaProgressTest extends AnyFunSuite:

  private def parseT(s: String): SType = InterfaceScope.parseSType(s)

  /** True iff a `.ssc` scala fragment parses with no code-block parse error. */
  private def srcParses(code: String): Boolean =
    Parser.parseScalaWithDiagnostic(code)._2.isEmpty

  // ════════════════════════════════════════════════════════════════════════
  // CURRENT BEHAVIOUR — passes today; documents the baseline.
  // ════════════════════════════════════════════════════════════════════════

  test("[now] HKT slot `F[_]` parses to a surface-only HigherKinded"):
    assert(parseT("F[_]") == SType.HigherKinded("F", 1))
    assert(parseT("F[_, _]") == SType.HigherKinded("F", 2))

  test("[now] placeholder `Map[Int, _]` parses but is NOT yet a type lambda"):
    // The `_` is preserved structurally as `Named(\"_\")`; it is not (yet) read
    // as a lambda parameter — no desugaring to `[X] =>> Map[Int, X]`.
    assert(parseT("Map[Int, _]") ==
      SType.Named("Map", List(SType.Int, SType.Named("_", Nil))))

  test("[now] Scala-3 native `[X] =>> F[X]` is unrepresented (falls back to Any)"):
    // No `SType.TypeLambda` case exists yet; the artifact parser can't model it.
    assert(parseT("[X] =>> List[X]") == SType.Any)

  test("[now] HKT type param `F[_]` parses in source (trait Functor[F[_]])"):
    assert(srcParses("trait Functor[F[_]]:\n  def unit[A](a: A): F[A]"))

  test("[now] Scala-3 native type lambda does NOT parse in a `type` alias yet"):
    assert(!srcParses("type IntMap = [V] =>> Map[Int, V]"))

  // ════════════════════════════════════════════════════════════════════════
  // TARGET — `pending` until `type-lambda-p2`. Flip each as it lands.
  // ════════════════════════════════════════════════════════════════════════

  test("[target] SType.TypeLambda(params, body) exists and shows as `=>>`"):
    pending // p2: add the SType case + canonical show

  test("[target] native `[X] =>> Map[Int, X]` parses to a TypeLambda"):
    pending // p2: parseSType + source parser

  test("[target] placeholder `Map[Int, _]` desugars to `[X] =>> Map[Int, X]`"):
    pending // p2: `_` → fresh param, left→right; equivalent to the native form

  test("[target] two placeholders `Either[_, _]` bind in source order"):
    pending // p2: `[A, B] =>> Either[A, B]`

  test("[target] type lambda round-trips through show/parseSType"):
    pending // p2: parse(t.show) == t for a TypeLambda

  test("[target] `type` alias with a type lambda parses in source (both surfaces)"):
    pending // p2: `type IntMap = [V] =>> Map[Int, V]` and `type IntMap = Map[Int, _]`

  test("[target] type lambda survives a `.sscc` v3 artifact round-trip"):
    pending // p2: artifact stability

  test("[target] beta-reduction `([X] =>> F[X])[A]` == `F[A]` in `ssc check`"):
    pending // p3 (optional): only if a typed backend / strict check motivates it
