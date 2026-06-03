package scalascript.interpreter.vm.jit

import scala.meta.{Pat, Term, Tree}
import scalascript.interpreter.{Interpreter, Value}

/** A reason a given `Defn.Def` would not JIT-compile. The lint walks the
 *  function body and classifies each known structural cliff into one of
 *  these reasons, so the user can refactor their code to a JIT-able shape.
 *
 *  Categories cover the common bail cliffs in `JavacJitBackend.tryCompile`
 *  and `EvalRuntime.compileExpr` — both lints share this classification so
 *  a "lint passes" report is equivalent to "JIT compiles" in steady state.
 *
 *  Phase 2 Commit 1: the inventory is the structural cliffs the lint can
 *  detect from a single-pass AST walk. Future commits will surface the
 *  remaining "unknown shape" bails by factoring out the actual recogniser
 *  predicates from JavacJitBackend so the lint can ask them directly. */
sealed trait JitBailReason:
  def description: String
  /** Suggested refactor when one applies. Some cliffs are structural (e.g.
   *  varargs in a hot loop) — there's no easy rewrite, so this is `None`. */
  def suggestedFix: Option[String]

object JitBailReason:
  case object TryCatch extends JitBailReason:
    val description = "function body contains `try`/`catch`; the JIT backend " +
      "does not emit Java exception tables for user-handled exceptions"
    val suggestedFix = Some("hoist the try/catch around the call site so the " +
      "hot inner body is exception-free")
  case object EffectReturn extends JitBailReason:
    val description = "function returns an algebraic effect (`A ! Eff` or " +
      "Computation-typed); effect handlers run in the tree-walk interpreter only"
    val suggestedFix = None
  case object UsingParams extends JitBailReason:
    val description = "function has `(using …)` parameter clauses; the JIT " +
      "backend does not yet resolve typeclass dispatch at compile time"
    val suggestedFix = Some("pass the resolved instance explicitly as a " +
      "regular parameter — `def f(x: Int)(using ord: Ord[Int])` → `def f(x: Int, ord: Ord[Int])`")
  case object VarargParam extends JitBailReason:
    val description = "function has a vararg parameter (last paramType ends " +
      "with `*`); the JIT compiles fixed-arity signatures only"
    val suggestedFix = Some("convert the vararg site to take an explicit " +
      "`List[T]` or `Seq[T]` argument")
  case object PatternGuard extends JitBailReason:
    val description = "function body has `case x if cond => …` — pattern " +
      "guards aren't lowered into the generated switch/dispatch"
    val suggestedFix = Some("move the guard into the arm body: `case x => " +
      "if cond then …; else <next-arm-body>`")
  case object NonAdtScrutinee extends JitBailReason:
    val description = "match scrutinee is not a parameter (cross-arm sharing " +
      "via a let-binding or compound expr) — the JIT requires direct param dispatch"
    val suggestedFix = Some("hoist the scrutinee out: `val s = ...; s match { … }` " +
      "→ `def helper(s: T) = s match { … }`")
  case object NonExtractPattern extends JitBailReason:
    val description = "pattern is not a simple `Ctor(bindings)` extract — " +
      "literal, alternative, type-test, or `@`-binding patterns aren't compiled"
    val suggestedFix = None
  case object MixedReturnType extends JitBailReason:
    val description = "function returns a mix of Int and Double across match " +
      "arms; the JIT picks one numeric type for the entire body"
    val suggestedFix = Some("widen all arms to Double by writing `.toDouble` " +
      "explicitly, or keep them all Int")
  case object UnknownShape extends JitBailReason:
    val description = "function would not JIT (no specific structural cliff " +
      "detected — either a Term shape the JIT walker doesn't emit, or the " +
      "JIT environment itself is broken: `ToolProvider.getSystemJavaCompiler` " +
      "returns null when running on a JRE without `tools.jar` / `javac`)"
    val suggestedFix = Some("verify the host JVM is a JDK (not a JRE) with " +
      "`javac --version`; on macOS the bundled `ssc` ships its own JDK so " +
      "this only affects custom launchers")
  case class Compound(reasons: List[JitBailReason]) extends JitBailReason:
    val description = reasons.map(_.description).mkString("; ")
    val suggestedFix = None

/** A single Defn.Def's lint result. `bailReasons` is empty when the def
 *  would JIT successfully (or already has — JitBackend caches compile
 *  results and the lint asks the live cache). */
final case class JitLintReport(
  defName:     String,
  defLine:     Option[Int],
  bailReasons: List[JitBailReason]
):
  def willJit: Boolean = bailReasons.isEmpty
  def humanReadable: String =
    val header = s"def $defName${defLine.fold("")(l => s" (line $l)")}"
    if willJit then s"$header  [JIT OK]"
    else
      val body = bailReasons.map { r =>
        val fix = r.suggestedFix.fold("")(s => s"\n      fix: $s")
        s"  - ${r.description}$fix"
      }.mkString("\n")
      s"$header  [will NOT JIT]\n$body"

/** Analyser that walks the interpreter's globals after a module is loaded
 *  and reports JIT-compilability for every Defn.Def. Reuses the live
 *  `JitBackend.default.tryCompile` cache as the ground-truth predicate so
 *  the lint and the runtime can never diverge silently.
 *
 *  Typical use:
 *  {{{
 *    val interp = Interpreter(System.out)
 *    interp.runSections(parseModule(src))
 *    val report = JitLint.lintInterpreter(interp)
 *    if report.exists(!_.willJit) then
 *      System.err.println("Some functions will not JIT — see above")
 *  }}}
 *
 *  Phase 2 Commit 1 limitations:
 *  - The classifier sees only what an AST walk of `f.body` can detect.
 *    Some bails happen deeper inside the walker (e.g. an unsupported
 *    Term.ApplyInfix op) and surface as `UnknownShape`.
 *  - Detail beyond the listed JitBailReason categories needs the
 *    JavacJitBackend recognisers to be factored out into pure predicates;
 *    deferred to Phase 2 Commit 2.
 *  - Only top-level FunVs are linted; closures and locally-defined defs
 *    that never reach `interp.globals` are skipped. */
object JitLint:
  /** Lint every top-level `def` in the interpreter's globals. Returns one
   *  report per FunV, sorted by name. Pure (no side effects on the
   *  interpreter — uses the existing tryCompile cache). */
  def lintInterpreter(interp: Interpreter): List[JitLintReport] =
    interp.globals.iterator
      .collect { case (name, fn: Value.FunV) => (name, fn) }
      .toList
      .sortBy(_._1)
      .map { case (name, fn) => lintFun(name, fn, interp) }

  /** Lint a single FunV — invoked from `lintInterpreter` or directly. */
  def lintFun(name: String, fn: Value.FunV, interp: Interpreter): JitLintReport =
    val line = posLineOf(fn.body)
    val backendResult = JitBackend.default.tryCompile(fn, interp)
    if backendResult != null then
      // The backend successfully compiled — no static bail to report.
      JitLintReport(name, line, Nil)
    else
      val reasons = classifyBailReasons(fn)
      val nonEmpty =
        if reasons.nonEmpty then reasons
        else List(JitBailReason.UnknownShape)
      JitLintReport(name, line, nonEmpty)

  /** Walk `fn.body` + the param/return metadata and collect every visible
   *  structural cliff. Returns Nil if no obvious cliff is detected — the
   *  caller then reports `UnknownShape`. */
  private def classifyBailReasons(fn: Value.FunV): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    if fn.usingParams.nonEmpty then buf += JitBailReason.UsingParams
    if fn.paramTypes.exists(_.endsWith("*")) then buf += JitBailReason.VarargParam
    if fn.returnsThrows then buf += JitBailReason.EffectReturn
    walkForCliffs(fn.body, buf)
    buf.toList.distinct

  /** Recursive AST traversal that pushes a `JitBailReason` for each visible
   *  cliff. Falls back to `Tree.children.foreach(walkForCliffs(_, …))` so
   *  every node is examined exactly once. */
  private def walkForCliffs(t: Tree, buf: scala.collection.mutable.ListBuffer[JitBailReason]): Unit =
    t match
      case _: Term.Try =>
        buf += JitBailReason.TryCatch
      case tm: Term.Match =>
        // Scrutinee must be a Term.Name resolving to a parameter (the JIT's
        // walkMatchBody requires this). A compound expr forces a tree-walk.
        tm.expr match
          case _: Term.Name => () // ok shape; deeper-walker may still bail
          case _            => buf += JitBailReason.NonAdtScrutinee
        tm.casesBlock.cases.foreach { c =>
          if c.cond.nonEmpty then buf += JitBailReason.PatternGuard
          c.pat match
            case _: Pat.Extract => () // standard ctor pattern; ok
            case _: Pat.Var     => () // bare binding; ok in catch-all arms
            case _: Pat.Wildcard => () // ok in catch-all arms
            case _              => buf += JitBailReason.NonExtractPattern
          walkForCliffs(c.body, buf)
        }
      case _ => t.children.foreach(walkForCliffs(_, buf))

  /** Best-effort source line extraction. scala.meta carries `Position` on
   *  every Tree node parsed from source; the start row is 0-indexed so we
   *  +1 for human display. Returns None for synthetic trees with no pos. */
  private def posLineOf(t: Tree): Option[Int] =
    val p = t.pos
    if p == scala.meta.inputs.Position.None then None
    else Some(p.startLine + 1)
