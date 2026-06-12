package scalascript.typer

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.InterfaceScope
import scalascript.ast.{Content, Section, SsccFormat}
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

  test("[now] Scala-3 native `[X] =>> List[X]` parses to a TypeLambda (p2)"):
    assert(parseT("[X] =>> List[X]") ==
      SType.TypeLambda(List("X"), SType.Named("List", List(SType.Named("X", Nil)))))

  test("[now] HKT type param `F[_]` parses in source (trait Functor[F[_]])"):
    assert(srcParses("trait Functor[F[_]]:\n  def unit[A](a: A): F[A]"))

  test("[now] Scala-3 native type lambda NOW parses in a `type` alias (p2)"):
    assert(srcParses("type IntMap = [V] =>> Map[Int, V]"))

  // ════════════════════════════════════════════════════════════════════════
  // TARGET — `pending` until `type-lambda-p2`. Flip each as it lands.
  // ════════════════════════════════════════════════════════════════════════

  test("[done] SType.TypeLambda(params, body) exists and shows as `=>>` (p2)"):
    assert(SType.TypeLambda(List("X"),
      SType.Named("F", List(SType.Named("X", Nil)))).show == "[X] =>> F[X]")

  test("[done] native `[X] =>> Map[Int, X]` parses to a TypeLambda (p2)"):
    assert(parseT("[X] =>> Map[Int, X]") ==
      SType.TypeLambda(List("X"),
        SType.Named("Map", List(SType.Int, SType.Named("X", Nil)))))

  test("[done] multi-param `[A, B] =>> Map[B, A]` binds both params (p2)"):
    assert(parseT("[A, B] =>> Map[B, A]") ==
      SType.TypeLambda(List("A", "B"),
        SType.Named("Map", List(SType.Named("B", Nil), SType.Named("A", Nil)))))

  test("[done] type lambda round-trips through show/parseSType (p2)"):
    val t = SType.TypeLambda(List("V"),
      SType.Named("Map", List(SType.Int, SType.Named("V", Nil))))
    assert(parseT(t.show) == t)

  test("[done] `type` alias with a type lambda parses in source — both surfaces (p2)"):
    assert(srcParses("type IntMap = [V] =>> Map[Int, V]"))
    assert(srcParses("type IntMap = Map[Int, _]"))

  test("[done] placeholder alias `Map[Int, _]` desugars to native `=>>` at parse (p2b)"):
    // The source parser rewrites a placeholder alias to the canonical native form
    // (an alias RHS with `_` is always a lambda — no existentials). parseSType (the
    // interface-artifact parser) still keeps `Map[String, _]` as a wildcard Named,
    // because it has no use-site context to disambiguate — see the `[now]` test above.
    val node = Parser.parseScalaSource("type IntKey = Map[Int, _]\ndef f(): IntKey[Long] = ???")
    assert(node.exists(_.tree.toString.contains("[A] =>> Map[Int, A]")),
      node.map(_.tree.toString).getOrElse("parse failed"))

  test("[done] two placeholders `Either[_, _]` bind left→right at parse (p2b)"):
    val node = Parser.parseScalaSource("type E = Either[_, _]")
    assert(node.exists(_.tree.toString.contains("[A, B] =>> Either[A, B]")),
      node.map(_.tree.toString).getOrElse("parse failed"))

  test("[done] placeholder alias nested in an `object` desugars (nested-aliases)"):
    // The desugar now recurses into template bodies, so an alias inside an object
    // body is rewritten to native `=>>` — without this, jvm codegen reads the
    // member alias as a wildcard that "does not take type parameters".
    val node = Parser.parseScalaSource(
      "object M:\n  type IntKey = Map[Int, _]\n  def f(): IntKey[Long] = ???")
    assert(node.exists(_.tree.toString.contains("[A] =>> Map[Int, A]")),
      node.map(_.tree.toString).getOrElse("parse failed"))

  test("[done] placeholder alias nested in a `trait` desugars (nested-aliases)"):
    val node = Parser.parseScalaSource("trait T:\n  type E = Either[_, _]")
    assert(node.exists(_.tree.toString.contains("[A, B] =>> Either[A, B]")),
      node.map(_.tree.toString).getOrElse("parse failed"))

  test("[done] placeholder alias nested two levels deep desugars (nested-aliases)"):
    // Recursion is depth-unbounded: an alias inside an object inside an object.
    val node = Parser.parseScalaSource(
      "object Outer:\n  object Inner:\n    type IntKey = Map[Int, _]")
    assert(node.exists(_.tree.toString.contains("[A] =>> Map[Int, A]")),
      node.map(_.tree.toString).getOrElse("parse failed"))

  /** Parse → write `.sscc` v3 → read back → reconstructed source of the first
   *  code block's re-parsed tree. The `.sscc` read re-parses from a token stream
   *  and applies the placeholder type-lambda desugar, so both surfaces come back
   *  as `=>>` (native via its stored token, placeholder via the read-side desugar). */
  private def ssccRoundtripBlockTree(ssc: String): String =
    val m  = Parser.parse(ssc)
    val m2 = SsccFormat.read(SsccFormat.write(m)).fold(e => sys.error(s".sscc read: $e"), identity)
    def blocks(s: Section): List[Content.CodeBlock] =
      s.content.collect { case c: Content.CodeBlock => c } ++ s.subsections.flatMap(blocks)
    m2.sections.flatMap(blocks).headOption
      .flatMap(_.tree).map(_.tree.toString).getOrElse("<no code block tree>")

  test("[done] type lambda survives a `.sscc` v3 artifact round-trip"):
    // Native `=>>` round-trips via its stored `=>>` token.
    val native = ssccRoundtripBlockTree(
      "# M\n\n```scalascript\ntype IntMap = [V] =>> Map[Int, V]\n```\n")
    assert(native.contains("=>>"), s"native lambda lost in round-trip:\n$native")
    // Placeholder `Map[Int, _]` round-trips because the read path applies the
    // same desugar the direct Parser parse does (otherwise it reverts to a wildcard).
    val placeholder = ssccRoundtripBlockTree(
      "# M\n\n```scalascript\ntype IntKey = Map[Int, _]\ndef f(): IntKey[Long] = ???\n```\n")
    assert(placeholder.contains("[A] =>> Map[Int, A]"),
      s"placeholder lambda lost in round-trip:\n$placeholder")

  test("[done] beta-reduction `([X] =>> F[X])[A]` == `F[A]` (p3 semantics)"):
    val lam = SType.TypeLambda(List("X"), SType.Named("F", List(SType.Named("X", Nil))))
    assert(lam.applyTo(List(SType.Named("A", Nil))) ==
      SType.Named("F", List(SType.Named("A", Nil))))

  test("[done] multi-param β-reduction `([A, B] =>> Map[B, A])[Int, String]` reorders"):
    val lam = SType.TypeLambda(List("A", "B"),
      SType.Named("Map", List(SType.Named("B", Nil), SType.Named("A", Nil))))
    assert(lam.applyTo(List(SType.Int, SType.String)) ==
      SType.Named("Map", List(SType.String, SType.Int)))

  test("[done] β-reduction respects shadowing — inner lambda rebinds the name"):
    // `[X] =>> ([X] =>> X)` applied to `A` must NOT substitute the inner `X`.
    val inner = SType.TypeLambda(List("X"), SType.Named("X", Nil))
    val outer = SType.TypeLambda(List("X"), inner)
    assert(outer.applyTo(List(SType.Int)) == inner)

  test("[done] `ssc check` β-reduces a type-lambda alias at the use site"):
    // Native + placeholder surfaces both reduce; a use-site arity mismatch errors.
    def errorsOf(src: String): List[String] =
      val m = Parser.parse(s"# T\n\n```scalascript\n$src\n```\n")
      Typer.typeCheckWithInterfaces(m, interfaces = Map.empty, strict = false).errors.map(_.msg)
    // Correct arity: no type-lambda arity error.
    val ok = errorsOf(
      "type IntKey = [V] =>> Map[Int, V]\ndef f(): IntKey[Long] = ???")
    assert(!ok.exists(_.contains("type argument")), s"unexpected arity error: $ok")
    // Placeholder alias (desugared to a lambda by the parser) reduces the same way.
    val okPlaceholder = errorsOf(
      "type IntKey = Map[Int, _]\ndef f(): IntKey[Long] = ???")
    assert(!okPlaceholder.exists(_.contains("type argument")),
      s"unexpected arity error: $okPlaceholder")
    // Wrong arity: 2 args to a 1-param lambda → a clear error.
    val bad = errorsOf(
      "type IntKey = [V] =>> Map[Int, V]\ndef f(): IntKey[Long, String] = ???")
    assert(bad.exists(m => m.contains("IntKey") && m.contains("type argument")),
      s"expected a type-lambda arity error, got: $bad")
