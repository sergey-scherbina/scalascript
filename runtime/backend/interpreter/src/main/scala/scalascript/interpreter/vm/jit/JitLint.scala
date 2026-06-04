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

/** Side-by-side lint result for both backends, produced by
 *  `JitLint.lintInterpreterCompare`.  Useful for surfacing functions that
 *  JIT on Javac but not ASM (or vice-versa) so ASM-parity regressions are
 *  visible without running a full benchmark. */
final case class JitLintCompareReport(
  defName: String,
  defLine: Option[Int],
  javac:   JitLintReport,
  asm:     JitLintReport
):
  def bothJit:      Boolean = javac.willJit  && asm.willJit
  def asmOnlyFails: Boolean = javac.willJit  && !asm.willJit
  def javacOnlyFails: Boolean = !javac.willJit && asm.willJit
  def bothFail:     Boolean = !javac.willJit && !asm.willJit
  def anyFail:      Boolean = !javac.willJit || !asm.willJit

  def humanReadable: String =
    val header   = s"def $defName${defLine.fold("")(l => s" (line $l)")}"
    val javacTag = if javac.willJit then "[JAVAC OK]" else "[JAVAC FAIL]"
    val asmTag   = if asm.willJit   then "[ASM OK]"   else "[ASM FAIL]"
    val sb = new StringBuilder(s"$header  $javacTag $asmTag")
    if bothFail && javac.bailReasons == asm.bailReasons then
      appendReasons("  both", javac.bailReasons, sb)
    else
      if !javac.willJit then appendReasons("  JAVAC", javac.bailReasons, sb)
      if !asm.willJit   then appendReasons("  ASM",   asm.bailReasons,   sb)
    sb.toString

  private def appendReasons(
    label:   String,
    reasons: List[JitBailReason],
    sb:      StringBuilder
  ): Unit =
    sb.append(s"\n$label:")
    reasons.foreach { r =>
      sb.append(s"\n    - ${r.description}")
      r.suggestedFix.foreach(f => sb.append(s"\n      fix: $f"))
    }

/** Pure predicates shared between the structural bail classifier and each
 *  `JitBackend` implementation.  Keeping the logic in one place prevents the
 *  lint and the backends from silently diverging after a backend change.
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

  /** Walk `fn.body` and the param/return metadata and collect every visible
   *  structural bail cliff.  Mirrors `JavacJitBackend.doCompile`'s early-bail
   *  predicates; backends may call this then append their own specifics.
   *  Returns Nil when no obvious cliff is found — caller reports UnknownShape. */
  def classifyBailReasons(fn: Value.FunV): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    if fn.params.isEmpty then buf += JitBailReason.ZeroParams
    else if fn.params.length > 2 then buf += JitBailReason.TooManyParams(fn.params.length)
    if fn.usingParams.nonEmpty then buf += JitBailReason.UsingParams
    if fn.paramTypes.exists(_.endsWith("*")) then buf += JitBailReason.VarargParam
    if fn.returnsThrows then buf += JitBailReason.EffectReturn
    if isBoolReturning(fn.body) then buf += JitBailReason.BoolBody
    walkForBailCliffs(fn.body, buf)
    buf.toList.distinct

  /** Recursive AST traversal: pushes a `JitBailReason` for each visible
   *  structural cliff.  Falls back to `Tree.children.foreach(…)` so every
   *  node is visited exactly once. */
  def walkForBailCliffs(t: Tree, buf: scala.collection.mutable.ListBuffer[JitBailReason]): Unit =
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
          walkForBailCliffs(c.body, buf)
        }
      case _ => t.children.foreach(walkForBailCliffs(_, buf))

/** Analyser that walks the interpreter's globals after a module is loaded
 *  and reports JIT-compilability for every Defn.Def.
 *
 *  `lintFun` and `lintInterpreter` accept an explicit `backend` parameter
 *  (defaulting to `JitBackend.default`) so callers can target Javac, ASM, or
 *  any future backend without changing the env var.  `lintInterpreterCompare`
 *  runs both Javac and ASM in a single pass and returns a side-by-side diff.
 *
 *  Ground truth is always `backend.tryCompile` — the live compile cache —
 *  so the lint and the runtime can never diverge silently.  When compilation
 *  fails the structural reason is derived from `backend.classifyBailReasons`,
 *  which each backend may specialise beyond the shared `JitPredicates` base. */
object JitLint:

  /** Lint every top-level `def` in the interpreter's globals against
   *  `backend` (default: `JitBackend.default`).  Returns one report per
   *  FunV, sorted by name. */
  def lintInterpreter(
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): List[JitLintReport] =
    interp.globals.iterator
      .collect { case (name, fn: Value.FunV) => (name, fn) }
      .toList
      .sortBy(_._1)
      .map { case (name, fn) => lintFun(name, fn, interp, backend) }

  /** Lint a single FunV against `backend` (default: `JitBackend.default`).
   *  Invoked from `lintInterpreter` or directly. */
  def lintFun(
    name:    String,
    fn:      Value.FunV,
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): JitLintReport =
    val line = posLineOf(fn.body)
    if backend.tryCompile(fn, interp) != null then
      JitLintReport(name, line, Nil)
    else
      val reasons = backend.classifyBailReasons(fn)
      JitLintReport(name, line, if reasons.nonEmpty then reasons else List(JitBailReason.UnknownShape))

  /** Run both `JavacJitBackend` and `AsmJitBackend` on every top-level def
   *  and return a side-by-side comparison.  Functions that compile on Javac
   *  but not ASM (or vice-versa) are immediately visible without a benchmark
   *  run. */
  def lintInterpreterCompare(interp: Interpreter): List[JitLintCompareReport] =
    interp.globals.iterator
      .collect { case (name, fn: Value.FunV) => (name, fn) }
      .toList
      .sortBy(_._1)
      .map { case (name, fn) =>
        val line   = posLineOf(fn.body)
        val javacR = lintFun(name, fn, interp, JavacJitBackend)
        val asmR   = lintFun(name, fn, interp, AsmJitBackend)
        JitLintCompareReport(name, line, javacR, asmR)
      }

  private def posLineOf(t: Tree): Option[Int] =
    val p = t.pos
    if p == scala.meta.inputs.Position.None then None
    else Some(p.startLine + 1)
