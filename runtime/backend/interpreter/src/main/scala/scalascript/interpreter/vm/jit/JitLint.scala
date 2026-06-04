package scalascript.interpreter.vm.jit

import scala.meta.{Lit, Pat, Term, Tree}
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
  /** While-loop condition is not a supported comparison or boolean term.
   *  `tryCompileWhileLong` requires the condition to be one of:
   *  `<`, `<=`, `>`, `>=`, `==`, `!=` (comparing slot expressions), or
   *  `&&` / `||` of such comparisons. */
  case object WhileCondShape extends JitBailReason:
    val description = "while-loop condition is not a supported comparison " +
      "(`<`, `<=`, `>`, `>=`, `==`, `!=`) or boolean (`&&`, `||`) of slot " +
      "expressions; the while-JIT only compiles arithmetic condition forms"
    val suggestedFix = Some("simplify the condition to a direct comparison of " +
      "loop variables or integer literals: `while i < n do …`")
  /** While-loop body contains a non-slot RHS in at least one assignment.
   *  `tryCompileWhileLong` requires every body assignment to be an arithmetic
   *  expression over loop slots and integer literals only. */
  case object WhileBodyShape extends JitBailReason:
    val description = "while-loop body has an assignment whose RHS is not a " +
      "supported arithmetic expression over loop variables and integer literals; " +
      "the while-JIT only compiles Int/Long slot arithmetic"
    val suggestedFix = Some("ensure every loop variable update is an arithmetic " +
      "expression over the same loop variables: `i = i + 1`, `acc = acc + x`")

/** A single while-loop's lint result.  `bailReasons` is empty when the loop
 *  was compiled by the JIT successfully (or is cached as compiled).
 *
 *  `condLine` is the source line of the while condition; `bodyLine` is the
 *  line of the body block.  Both may be absent when position information
 *  is not attached (e.g. synthetic AST nodes in tests). */
final case class JitLintWhileReport(
  condLine:    Option[Int],
  bodyLine:    Option[Int],
  bailReasons: List[JitBailReason]
):
  def willJit: Boolean = bailReasons.isEmpty
  def humanReadable: String =
    val loc = (condLine, bodyLine) match
      case (Some(c), _) => s" (line $c)"
      case (_, Some(b)) => s" (body line $b)"
      case _            => ""
    val header = s"while$loc"
    if willJit then s"$header  [JIT OK]"
    else
      val body = bailReasons.map { r =>
        val fix = r.suggestedFix.fold("")(s => s"\n      fix: $s")
        s"  - ${r.description}$fix"
      }.mkString("\n")
      s"$header  [will NOT JIT]\n$body"

/** Side-by-side while-loop lint result for both backends, produced by
 *  `JitLint.lintWhileLoopsCompare`. */
final case class JitLintWhileCompareReport(
  condLine: Option[Int],
  bodyLine: Option[Int],
  javac:    JitLintWhileReport,
  asm:      JitLintWhileReport
):
  def bothJit:       Boolean = javac.willJit  && asm.willJit
  def asmOnlyFails:  Boolean = javac.willJit  && !asm.willJit
  def javacOnlyFails: Boolean = !javac.willJit && asm.willJit
  def bothFail:      Boolean = !javac.willJit && !asm.willJit
  def anyFail:       Boolean = !javac.willJit || !asm.willJit

  def humanReadable: String =
    val loc = (condLine, bodyLine) match
      case (Some(c), _) => s" (line $c)"
      case (_, Some(b)) => s" (body line $b)"
      case _            => ""
    val header   = s"while$loc"
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
    if fn.params.length > 2 then buf += JitBailReason.TooManyParams(fn.params.length)
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

  /** Classify why `tryCompileWhileLong` would fail for the given condition and
   *  body terms.  Returns Nil when no structural reason is detectable (the
   *  caller will report `UnknownShape`).
   *
   *  Two coarse structural reasons mirror the two bail points in
   *  `tryCompileWhileLong`:
   *  - `WhileCondShape` — condition is not a supported comparison/boolean form
   *  - `WhileBodyShape` — at least one body assignment has an unsupported RHS
   *
   *  These are surface-level checks only; the full walk is in
   *  `JavacJitBackend.walkLocalBoolCtx` / `walkLocalSlotCtx`.  A while loop
   *  that passes these checks may still fail at compile time for deeper
   *  reasons (e.g. a callee that doesn't resolve). */
  def classifyWhileBailReasons(cond: Term, body: Term): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    // Check condition shape: must be a comparison/boolean infix, optionally
    // wrapped in a 1-statement block.
    if !isWhileCondSupported(cond) then buf += JitBailReason.WhileCondShape
    // Check body shape: collect all assignments; for each one the RHS must
    // look like an arithmetic expression over names and literals.
    if !allBodyAssignsSupported(body) then buf += JitBailReason.WhileBodyShape
    buf.toList

  private def isWhileCondSupported(t: Term): Boolean = t match
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => isWhileCondSupported(inner)
        case _           => false
    case Term.ApplyInfix.After_4_6_0(_, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" => true
        case "&&" | "||" =>
          isWhileCondSupported(argClause.values.head)  // check at least one branch
        case _ => false
    case _ => false

  private def allBodyAssignsSupported(body: Term): Boolean =
    // Extract all Assign nodes from the body.
    val assigns = scala.collection.mutable.ListBuffer.empty[Term.Assign]
    collectAssigns(body, assigns)
    assigns.nonEmpty && assigns.forall(a => isSlotExpr(a.rhs))

  private def collectAssigns(t: Term, out: scala.collection.mutable.ListBuffer[Term.Assign]): Unit =
    t match
      case a: Term.Assign => out += a
      case b: Term.Block  => b.stats.foreach {
        case inner: Term => collectAssigns(inner, out)
        case _           => ()
      }
      case _ => ()

  /** True if `t` is an arithmetic expression over names and integer literals —
   *  the kind of RHS that `walkLocalSlotCtx` can translate. */
  private def isSlotExpr(t: Term): Boolean = t match
    case _: Term.Name     => true
    case _: Lit.Int       => true
    case _: Lit.Long      => true
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" | "&" | "|" | "^" | "<<" | ">>" | ">>>" =>
          isSlotExpr(lhs) && isSlotExpr(argClause.values.head)
        case _ => false
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "~" =>
      isSlotExpr(arg)
    case _: Term.Block if t.asInstanceOf[Term.Block].stats.lengthCompare(1) == 0 =>
      t.asInstanceOf[Term.Block].stats.head match
        case inner: Term => isSlotExpr(inner)
        case _           => false
    case _ => false

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
 *  which each backend may specialise beyond the shared `JitPredicates` base.
 *
 *  While-loop coverage (`lintWhileLoops`, `lintWhileLoopsCompare`) works by
 *  iterating `interp.whileJitCache`, which is populated during `runSections`
 *  as top-level while loops execute.  The cache stores a `WhileJitEntry` on
 *  success or the `WhileJitMiss` sentinel on failure; the lint reads these
 *  results directly without re-triggering compilation. */
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

  /** Lint every top-level while loop that was executed during `runSections`
   *  against `backend`.  The source of truth is `interp.whileJitCache`, which
   *  is an `IdentityHashMap[Term.While, AnyRef]` populated by `EvalRuntime`
   *  as loops execute:
   *  - `WhileJitEntry`  — loop was compiled successfully
   *  - `WhileJitMiss`   — compilation was attempted and failed
   *
   *  For missed compilations, `JitPredicates.classifyWhileBailReasons` provides
   *  a surface-level structural reason (condition or body shape mismatch).
   *
   *  Loops are sorted by condition line number (ascending) to produce a stable,
   *  readable report regardless of HashMap iteration order.
   *
   *  Note: loops that were never executed (e.g. inside an `if false` branch)
   *  are not visible here — they will not appear in the cache and cannot be
   *  linted without a separate static parse pass. */
  def lintWhileLoops(
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): List[JitLintWhileReport] =
    val buf = scala.collection.mutable.ListBuffer.empty[(Option[Int], JitLintWhileReport)]
    val it  = interp.whileJitCache.entrySet().iterator()
    while it.hasNext do
      val e        = it.next()
      val whileT   = e.getKey
      val condLine = posLineOf(whileT.expr)
      // e.getValue is either a WhileJitEntry (success) or the WhileJitMiss
      // sentinel (an opaque AnyRef, not a WhileJitEntry).  We probe using
      // tryCompileWhileLong so the same ground-truth cache applies regardless
      // of which backend we're linting against.
      val report = lintWhileSingle(whileT, interp, backend)
      buf += ((condLine, report))
    buf.toList.sortBy(_._1.getOrElse(Int.MaxValue)).map(_._2)

  /** Lint every top-level while loop against both `JavacJitBackend` and
   *  `AsmJitBackend`, returning a side-by-side comparison.  Loops that JIT
   *  on Javac but not ASM (or vice-versa) are flagged explicitly — useful
   *  for surfacing ASM-backend regressions before they show up at bench time. */
  def lintWhileLoopsCompare(interp: Interpreter): List[JitLintWhileCompareReport] =
    val buf = scala.collection.mutable.ListBuffer.empty[(Option[Int], JitLintWhileCompareReport)]
    val it  = interp.whileJitCache.entrySet().iterator()
    while it.hasNext do
      val entry   = it.next()
      val whileT  = entry.getKey
      val condLine = posLineOf(whileT.expr)
      val bodyLine = posLineOf(whileT.body)
      val javacR  = lintWhileSingle(whileT, interp, JavacJitBackend)
      val asmR    = lintWhileSingle(whileT, interp, AsmJitBackend)
      buf += ((condLine, JitLintWhileCompareReport(condLine, bodyLine, javacR, asmR)))
    buf.toList.sortBy(_._1.getOrElse(Int.MaxValue)).map(_._2)

  private def lintWhileSingle(
    whileT:  scala.meta.Term.While,
    interp:  Interpreter,
    backend: JitBackend
  ): JitLintWhileReport =
    val condLine = posLineOf(whileT.expr)
    val bodyLine = posLineOf(whileT.body)
    val assigns  = extractBodyAssigns(whileT.body)
    val entry    = backend.tryCompileWhileLong(whileT.expr, assigns._1, assigns._2, interp)
    if entry != null then
      JitLintWhileReport(condLine, bodyLine, Nil)
    else
      val reasons = JitPredicates.classifyWhileBailReasons(whileT.expr, whileT.body)
      JitLintWhileReport(condLine, bodyLine, if reasons.nonEmpty then reasons else List(JitBailReason.UnknownShape))

  /** Extract `(names, rhsTerms)` from a while body for use as the `names` and
   *  `rhs` arguments to `tryCompileWhileLong`.
   *
   *  In a compilable while body, top-level statements are `Term.Assign` nodes
   *  whose LHS is a `Term.Name`.  We collect those; non-assign statements
   *  produce an empty arrays pair, which will cause `tryCompileWhileLong` to
   *  return null (same as a structural bail). */
  private def extractBodyAssigns(body: scala.meta.Term): (Array[String], Array[scala.meta.Term]) =
    val assignments = scala.collection.mutable.ListBuffer.empty[(String, scala.meta.Term)]
    body match
      case b: scala.meta.Term.Block =>
        b.stats.foreach {
          case Term.Assign(Term.Name(n), rhs) => assignments += ((n, rhs))
          case _                              => ()
        }
      case Term.Assign(Term.Name(n), rhs) =>
        assignments += ((n, rhs))
      case _ => ()
    (assignments.map(_._1).toArray, assignments.map(_._2).toArray)

  private[jit] def posLineOf(t: scala.meta.Tree): Option[Int] =
    val p = t.pos
    if p == scala.meta.inputs.Position.None then None
    else Some(p.startLine + 1)
