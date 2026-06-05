package scalascript.interpreter.vm.jit

/** A typed reason why a given function or while-loop would not JIT-compile.
 *
 *  Used by all three JIT engines (VmCompiler→SscVm, JavacJitBackend,
 *  AsmJitBackend) and recorded via [[scalascript.interpreter.vm.JitMissStats]]
 *  with a per-engine label.
 *
 *  The `description` and `suggestedFix` fields are user-visible (e.g. printed
 *  by `ssc lint-jit` and `ssc check-jit-coverage`).
 *
 *  Each case's `tag` is a short machine-readable key suitable for grouping in
 *  miss-stats output. */
sealed trait JitBailReason:
  def description:  String
  def suggestedFix: Option[String]
  def tag:          String

object JitBailReason:

  // ── Structural / parameter shape ────────────────────────────────────────

  case object TryCatch extends JitBailReason:
    val tag         = "TryCatch"
    val description = "function body contains `try`/`catch`; the JIT backend " +
      "does not emit Java exception tables for user-handled exceptions"
    val suggestedFix = Some("hoist the try/catch around the call site so the " +
      "hot inner body is exception-free")

  case object EffectReturn extends JitBailReason:
    val tag         = "EffectReturn"
    val description = "function returns an algebraic effect (`A ! Eff` or " +
      "Computation-typed); effect handlers run in the tree-walk interpreter only"
    val suggestedFix = None

  case object UsingParams extends JitBailReason:
    val tag         = "UsingParams"
    val description = "function has `(using …)` parameter clauses; the JIT " +
      "backend does not yet resolve typeclass dispatch at compile time"
    val suggestedFix = Some("pass the resolved instance explicitly as a " +
      "regular parameter — `def f(x: Int)(using ord: Ord[Int])` → `def f(x: Int, ord: Ord[Int])`")

  case object VarargParam extends JitBailReason:
    val tag         = "VarargParam"
    val description = "function has a vararg parameter (last paramType ends " +
      "with `*`); the JIT compiles fixed-arity signatures only"
    val suggestedFix = Some("convert the vararg site to take an explicit " +
      "`List[T]` or `Seq[T]` argument")

  case class TooManyParams(n: Int) extends JitBailReason:
    val tag         = s"TooManyParams($n)"
    val description = s"function has $n parameters; the JIT compiles up to " +
      "4-parameter functions (1 ref, 1 long, 1 double, any combo up to 4)"
    val suggestedFix = Some("split into smaller helpers or fold the extra state " +
      "into an accumulator parameter")

  // ── Match / pattern shape ────────────────────────────────────────────────

  /** Guards in Int/Long-scrutinee matches are not compiled.
   *  Note: guards on ADT (InstanceV) scrutinees ARE compiled via the
   *  fallback if-chain emitter (`walkArmAsIfBranch`).  This reason is only
   *  reported when the backend actually fails to compile the function. */
  case object PatternGuard extends JitBailReason:
    val tag         = "PatternGuard"
    val description = "function body has `case x if cond => …` in a match on " +
      "an Int/Long/non-ADT scrutinee; guards on such matches are not compiled " +
      "(guards on ADT (InstanceV) scrutinee matches are supported)"
    val suggestedFix = Some("move the guard into the arm body: `case x => " +
      "if cond then …; else <next-arm-body>`")

  case object NonAdtScrutinee extends JitBailReason:
    val tag         = "NonAdtScrutinee"
    val description = "match scrutinee is not a parameter (cross-arm sharing " +
      "via a let-binding or compound expr) — the JIT requires direct param dispatch"
    val suggestedFix = Some("hoist the scrutinee out: `val s = ...; s match { … }` " +
      "→ `def helper(s: T) = s match { … }`")

  case object NonExtractPattern extends JitBailReason:
    val tag         = "NonExtractPattern"
    val description = "pattern is not a simple `Ctor(bindings)` extract — " +
      "literal, alternative, type-test, or `@`-binding patterns aren't compiled"
    val suggestedFix = None

  case object PatLiteralArm extends JitBailReason:
    val tag         = "PatLiteralArm"
    val description = "match arm uses a literal pattern (`case 0 =>`, `case \"x\" =>`); " +
      "literal patterns are not yet supported in the JIT match emitter"
    val suggestedFix = Some("rewrite as `case x if x == 0 =>` or restructure as an `if`-chain")

  // ── Return type ──────────────────────────────────────────────────────────

  /** The function body's top-level expression is a comparison or logical
   *  operator (`<`, `<=`, `>`, `>=`, `==`, `!=`, `&&`, `||`). The JIT
   *  always generates `long`-returning static methods; a bool-typed result
   *  would be silently misrepresented as Int 0/1 to callers that expect
   *  a `BoolV`.
   *  Lifted in Stage 2.1: Bool bodies are wrapped `if body then 1L else 0L`. */
  case object BoolBody extends JitBailReason:
    val tag         = "BoolBody"
    val description = "function body is a comparison or logical expression " +
      "(`<`, `<=`, `>`, `>=`, `==`, `!=`, `&&`, `||`); the JIT emits `long` " +
      "and the result would be misrepresented as Int 0/1 rather than BoolV"
    val suggestedFix = Some("wrap the comparison in an explicit `if`: " +
      "`def pred(x: Int): Boolean = x > 0` → `def pred(x: Int): Int = if x > 0 then 1 else 0`")

  case object MixedReturnType extends JitBailReason:
    val tag         = "MixedReturnType"
    val description = "function returns a mix of Int and Double across match " +
      "arms; the JIT picks one numeric type for the entire body"
    val suggestedFix = Some("widen all arms to Double by writing `.toDouble` " +
      "explicitly, or keep them all Int")

  /** Function returns a ref-typed value (InstanceV, StringV, etc.) and the
   *  VmCompiler's `RETREF` opcode is not yet implemented. */
  case object RefReturn extends JitBailReason:
    val tag         = "RefReturn"
    val description = "function returns a ref-typed value (InstanceV, StringV, etc.); " +
      "the SscVm `RET` opcode is Long-typed — a `RETREF` opcode has not yet been added"
    val suggestedFix = None

  // ── Call shapes ───────────────────────────────────────────────────────────

  /** Call to a `FunV` that was passed as a parameter, stored in a local val,
   *  or is a free name that resolves to a non-FunV value.
   *  Lifted in Stage 3 (CALLREF opcode). */
  case class HofCall(calleeName: String) extends JitBailReason:
    val tag         = "HofCall"
    val description = s"call to `$calleeName` which is a higher-order function " +
      "(FunV passed as parameter or HOF callback); the JIT requires a static " +
      "dispatch target — HOF calls via CALLREF are not yet supported"
    val suggestedFix = Some("inline the lambda body at the call site, or pass " +
      "the computation as a plain integer/ADT value instead of a function")

  /** Lambda / inner def closes over a variable from an outer scope.
   *  Non-capturing inner defs (p3/p4) already compile; this is the capturing case. */
  case class CapturedFreeName(name: String) extends JitBailReason:
    val tag         = "CapturedFreeName"
    val description = s"inner def or lambda captures `$name` from an enclosing scope; " +
      "the JIT compiles non-capturing inner defs only — closures over free variables " +
      "require Stage 3 CALLREF machinery"
    val suggestedFix = Some("add the captured variable as an explicit parameter: " +
      s"`def inner(x: Int, $name: T) = …` and pass it at each call site")

  case object LambdaValue extends JitBailReason:
    val tag         = "LambdaValue"
    val description = "anonymous `Term.Function` lambda used as a value (not immediately " +
      "applied inline); lambdas passed as arguments or stored in vals require Stage 3 " +
      "CALLREF machinery"
    val suggestedFix = Some("convert to a named top-level def and pass the def name")

  case class FreeNameUnresolvable(name: String) extends JitBailReason:
    val tag         = "FreeNameUnresolvable"
    val description = s"name `$name` is not a parameter, local val, sibling def, or " +
      "compilable top-level FunV; the JIT cannot emit a call with an unknown callee"
    val suggestedFix = Some(s"ensure `$name` is either a top-level `def` with " +
      "a JIT-compatible body, or pass it explicitly as a parameter")

  // ── Dispatch / parameter combo ───────────────────────────────────────────

  /** Both parameters are ref-typed (InstanceV / StringV / etc.); the JIT
   *  currently has no `ObjObjToLong/Double/Object` dispatch interface.
   *  Lifted in Stage 2.2. */
  case object RefRefParam extends JitBailReason:
    val tag         = "RefRefParam"
    val description = "both parameters are ref-typed (InstanceV / StringV); " +
      "the JIT bytecode backends have no `ObjObjToLong/Double/Object` dispatch interface yet"
    val suggestedFix = None

  // ── While loop ───────────────────────────────────────────────────────────

  case object WhileCondShape extends JitBailReason:
    val tag         = "WhileCondShape"
    val description = "while-loop condition is not a supported comparison " +
      "(`<`, `<=`, `>`, `>=`, `==`, `!=`) or boolean (`&&`, `||`) of slot " +
      "expressions; the while-JIT only compiles arithmetic condition forms"
    val suggestedFix = Some("simplify the condition to a direct comparison of " +
      "loop variables or integer literals: `while i < n do …`")

  case object WhileBodyShape extends JitBailReason:
    val tag         = "WhileBodyShape"
    val description = "while-loop body has an assignment whose RHS is not a " +
      "supported arithmetic expression over loop variables and integer literals; " +
      "the while-JIT only compiles Int/Long slot arithmetic"
    val suggestedFix = Some("ensure every loop variable update is an arithmetic " +
      "expression over the same loop variables: `i = i + 1`, `acc = acc + x`")

  // ── Misc ─────────────────────────────────────────────────────────────────

  case object UnknownShape extends JitBailReason:
    val tag         = "UnknownShape"
    val description = "function would not JIT (no specific structural cliff " +
      "detected — either a Term shape the JIT walker doesn't emit, or the " +
      "JIT environment itself is broken: `ToolProvider.getSystemJavaCompiler` " +
      "returns null when running on a JRE without `tools.jar` / `javac`)"
    val suggestedFix = Some("verify the host JVM is a JDK (not a JRE) with " +
      "`javac --version`; on macOS the bundled `ssc` ships its own JDK so " +
      "this only affects custom launchers")

  case class Compound(reasons: List[JitBailReason]) extends JitBailReason:
    val tag         = "Compound"
    val description = reasons.map(_.description).mkString("; ")
    val suggestedFix = None

  /** Backwards-compatibility wrapper for legacy free-form string bail reasons
   *  (used by VmCompiler bail sites not yet migrated to typed cases). */
  case class Other(reason: String) extends JitBailReason:
    val tag         = "Other"
    val description = reason
    val suggestedFix = None
