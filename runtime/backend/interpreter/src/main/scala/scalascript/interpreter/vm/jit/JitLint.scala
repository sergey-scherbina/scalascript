package scalascript.interpreter.vm.jit

import scala.meta.{Pat, Term, Tree}
import scalascript.interpreter.{Interpreter, Value}

/** A reason a given `Defn.Def` would not JIT-compile. The lint walks the
 *  function body and classifies each known structural cliff into one of
 *  these reasons, so the user can refactor their code to a JIT-able shape.
 *
 *  Categories cover the common bail cliffs in `JavacJitBackend.tryCompile`
 *  and `EvalRuntime.compileExpr` — both lints share this classification so
 *  a "lint passes" report is equivalent to "JIT compiles" in steady state. */
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
  /** Guards in Int/Long-scrutinee matches are not compiled — the JIT emits a
   *  Java switch on the type tag (ADT) or a direct long value; there is no
   *  per-case re-dispatch for failed guards in that path.
   *  Note: guards on ADT (InstanceV) scrutinees ARE compiled since the
   *  while-jit-ref-select-chain slice via a fallback if-chain emitter
   *  (`walkArmAsIfBranch`). This reason is only reported when the backend
   *  actually fails to compile the function. */
  case object PatternGuard extends JitBailReason:
    val description = "function body has `case x if cond => …` in a match on " +
      "an Int/Long/non-ADT scrutinee; guards on such matches are not compiled " +
      "(guards on ADT (InstanceV) scrutinee matches are supported)"
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
  /** The function body's top-level expression is a comparison or logical
   *  operator (`<`, `<=`, `>`, `>=`, `==`, `!=`, `&&`, `||`). The JIT
   *  always generates `long`-returning static methods; a bool-typed result
   *  would be silently misrepresented as Int 0/1 to callers that expect
   *  a `BoolV`. */
  case object BoolBody extends JitBailReason:
    val description = "function body is a comparison or logical expression " +
      "(`<`, `<=`, `>`, `>=`, `==`, `!=`, `&&`, `||`); the JIT emits `long` " +
      "and the result would be misrepresented as Int 0/1 rather than BoolV"
    val suggestedFix = Some("wrap the comparison in an explicit `if`: " +
      "`def pred(x: Int): Boolean = x > 0` → `def pred(x: Int): Int = if x > 0 then 1 else 0`")
  /** No parameters — the JIT requires at least one typed parameter to build
   *  a fixed-arity static method signature. */
  case object ZeroParams extends JitBailReason:
    val description = "function has zero parameters; the JIT requires at least " +
      "one Int/Long/Double/ADT parameter to generate a fixed-arity static method"
    val suggestedFix = None
  /** More than two parameters — the JIT supports 1- and 2-parameter functions
   *  (covering both the single-param and mixed Long+ref/Long+Long pairs).
   *  3+ param functions always fall back to SscVm.exec. */
  case class TooManyParams(n: Int) extends JitBailReason:
    val description = s"function has $n parameters; the JIT compiles 1- and " +
      "2-parameter functions only (1 ref, 1 long, 1 double, long+ref, long+long, ref+long)"
    val suggestedFix = Some("split into smaller helpers or fold the extra state " +
      "into a 2-param while loop (accumulator + loop variable)")
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

/** Pure predicates shared between `JitLint.classifyBailReasons` and
 *  `JavacJitBackend.doCompile`. Keeping the logic in one place prevents
 *  the lint and the backend from silently diverging after a backend change.
 *
 *  All functions are pure (no interpreter access, no side effects) and
 *  operate on scala.meta AST nodes only. */
private[jit] object JitPredicates:
  /** True iff the top-level result type of `t` is Boolean (comparison or
   *  short-circuit logical). `JavacJitBackend` always emits `long`-returning
   *  static methods; a bool-typed body would be misrepresented as Int 0/1. */
  def isBoolReturning(t: Term): Boolean = t match
    case Term.ApplyInfix.After_4_6_0(_, op, _, _) =>
      val s = op.value
      s == "<" || s == "<=" || s == ">" || s == ">=" || s == "==" || s == "!=" || s == "&&" || s == "||"
    case ti: Term.If =>
      isBoolReturning(ti.thenp) || isBoolReturning(ti.elsep)
    case _ => false

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
 *  The classifier calls the same predicates as `JavacJitBackend.doCompile`
 *  (via `JitPredicates`) so the lint and the backend cannot diverge silently.
 *  Remaining "unknown shape" bails (Term shapes the walker can't classify
 *  without running `doCompile`) still surface as `UnknownShape`. */
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
      JitLintReport(name, line, Nil)
    else
      val reasons = classifyBailReasons(fn)
      val nonEmpty =
        if reasons.nonEmpty then reasons
        else List(JitBailReason.UnknownShape)
      JitLintReport(name, line, nonEmpty)

  /** Walk `fn.body` + the param/return metadata and collect every visible
   *  structural cliff. The checks mirror `JavacJitBackend.doCompile`'s
   *  early-bail predicates so the lint and the backend stay in sync.
   *  Returns Nil if no obvious cliff is detected — the caller then reports
   *  `UnknownShape`. */
  private def classifyBailReasons(fn: Value.FunV): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    // Param-count check mirrors doCompile's `if f.params.length != 1 && … != 2`.
    if fn.params.isEmpty then buf += JitBailReason.ZeroParams
    else if fn.params.length > 2 then buf += JitBailReason.TooManyParams(fn.params.length)
    if fn.usingParams.nonEmpty then buf += JitBailReason.UsingParams
    if fn.paramTypes.exists(_.endsWith("*")) then buf += JitBailReason.VarargParam
    if fn.returnsThrows then buf += JitBailReason.EffectReturn
    // Bool-body check mirrors doCompile's `if isBoolReturning(f.body) then return null`.
    if JitPredicates.isBoolReturning(fn.body) then buf += JitBailReason.BoolBody
    walkForCliffs(fn.body, buf)
    buf.toList.distinct

  /** Recursive AST traversal that pushes a `JitBailReason` for each visible
   *  structural cliff. Falls back to `Tree.children.foreach(walkForCliffs(_, …))`
   *  so every node is visited exactly once. */
  private def walkForCliffs(t: Tree, buf: scala.collection.mutable.ListBuffer[JitBailReason]): Unit =
    t match
      case _: Term.Try =>
        buf += JitBailReason.TryCatch
      case tm: Term.Match =>
        tm.expr match
          case _: Term.Name => ()
          case _            => buf += JitBailReason.NonAdtScrutinee
        tm.casesBlock.cases.foreach { c =>
          if c.cond.nonEmpty then buf += JitBailReason.PatternGuard
          c.pat match
            case _: Pat.Extract  => ()
            case _: Pat.Var      => ()
            case _: Pat.Wildcard => ()
            case _               => buf += JitBailReason.NonExtractPattern
          walkForCliffs(c.body, buf)
        }
      case _ => t.children.foreach(walkForCliffs(_, buf))

  private def posLineOf(t: Tree): Option[Int] =
    val p = t.pos
    if p == scala.meta.inputs.Position.None then None
    else Some(p.startLine + 1)
