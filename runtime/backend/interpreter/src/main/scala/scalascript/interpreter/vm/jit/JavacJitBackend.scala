package scalascript.interpreter.vm.jit

import java.lang.invoke.{MethodHandles, MethodType}
import javax.tools.{ToolProvider, JavaFileObject, SimpleJavaFileObject, ForwardingJavaFileManager, JavaFileManager, FileObject}
import java.io.{ByteArrayOutputStream, OutputStream}
import java.net.URI

import scala.meta.{Term, Lit, Pat, Stat, Defn}
import scalascript.interpreter.Value

/** Phase C: bytecode JIT for pure-int self-recursive functions.
 *
 *  Compiles a `Value.FunV` whose body is a closed expression over `Long`-typed
 *  params, `Lit.Int`/`Lit.Long`, `Term.ApplyInfix` (arith + comparison + `&&`
 *  / `||`), `Term.If`, and a self-recursive call `name(args)` into Java source,
 *  compiles that via the in-JDK `javax.tools.JavaCompiler`, loads the class
 *  with a child class loader, and returns a `MethodHandle` for the static
 *  method. The handle is cached by the FunV body's AST identity.
 *
 *  The generated Java method has signature `static long name(long, long?)` and
 *  recurses via the same class's static method, which HotSpot inlines and
 *  optimizes exactly like ordinary recursive Java code. The dispatch overhead
 *  of `SscVm.exec`'s opcode while-loop is eliminated.
 *
 *  Subset (initial spike):
 *    - 1 or 2 `Int`/`Long`-typed params.
 *    - Body returns `Int`/`Long`.
 *    - Body shape: `Lit.Int`/`Lit.Long`, `Term.Name` (param read), `Term.If`,
 *      `Term.ApplyInfix` (+, -, *, /, %, <, <=, >, >=, ==, !=, &&, ||),
 *      `Term.Apply(Term.Name(name), args)` — self-recursive call only.
 *    - Free names (non-param) bail. Mutual recursion bails.
 *
 *  Anything outside the subset returns `null`; the caller falls back to the
 *  register-VM `SscVm.exec` path (which the JitRuntime always has).
 *
 *  Default **ON** since all three Phase C slices (int-arith, ADT match, TCO
 *  loop emission) shipped same-session A/B-proven 2026-06-02 (recursionFib
 *  23.8×, recursionTco 33.6×, recursiveEval 2.45×) with the full 1205-test
 *  suite green in both modes. Opt out via `SSC_JIT_BYTECODE=off` /
 *  `-Dssc.jit.bytecode=off` if a regression needs A/B isolation. */
object JavacJitBackend extends JitBackend:

  override val id: String = "javac"

  /** Default **ON**. Opt out via env `SSC_JIT_BYTECODE=off` or the parallel
   *  system property `-Dssc.jit.bytecode=off` (JMH forks — `-D` propagates
   *  through `-jvmArgsAppend`, env vars do not always). The off-switch
   *  matches the pattern set by `SSC_JIT` and `SSC_FASTTIER`. */
  override val enabled: Boolean =
    !sys.env.get("SSC_JIT_BYTECODE").map(_.toLowerCase).contains("off") &&
      !sys.props.get("ssc.jit.bytecode").map(_.toLowerCase).contains("off")

  /** Cache by FunV body AST identity. Value is a `JitResult` on hit or the
   *  `BailSentinel` on miss (so we don't re-attempt compilation for the same
   *  body). Synchronized via the cache monitor. */
  private val cache = new java.util.IdentityHashMap[scala.meta.Term, AnyRef]()
  private val BailSentinel: AnyRef = new AnyRef

  override def tryCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): JitResult | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length != 1 && f.params.length != 2 then return null
    val body = f.body
    cache.synchronized {
      val cached = cache.get(body)
      if cached != null then
        return if cached eq BailSentinel then null else cached.asInstanceOf[JitResult]
    }
    val result = doCompile(f, interp)
    cache.synchronized {
      cache.put(body, if result == null then BailSentinel else result.asInstanceOf[AnyRef])
    }
    result

  /** True iff `t` contains a `Lit.Double` anywhere — heuristic for typing
   *  the function as Double (params + return as `double`). Scala promotes
   *  Int operands to Double when mixed in arith; a body with any Double
   *  literal is Double-typed end-to-end. */
  private def bodyHasDoubleLit(t: Term): Boolean =
    var hit = false
    def walk(tree: scala.meta.Tree): Unit =
      if hit then ()
      else tree match
        case _: Lit.Double => hit = true
        case _             => tree.children.foreach(walk)
    walk(t)
    hit

  /** True iff the body's top-level result is a Boolean (comparison or
   *  short-circuit). `JavacJitBackend` returns `long` from every generated method
   *  and the caller wraps as `IntV(0|1)` — a Boolean-returning fn would then
   *  be misrepresented to consumers expecting `BoolV`. Bail in that case so
   *  the SscVm.exec / tree-walk path handles those correctly. */
  private def isBoolReturning(t: Term): Boolean = t match
    case Term.ApplyInfix.After_4_6_0(_, op, _, _) =>
      val s = op.value
      s == "<" || s == "<=" || s == ">" || s == ">=" || s == "==" || s == "!=" || s == "&&" || s == "||"
    case ti: Term.If =>
      isBoolReturning(ti.thenp) || isBoolReturning(ti.elsep)
    case _ => false

  /** Returns the fully-qualified name of the JitInterface that matches the
   *  compiled function's signature, or null for unsupported shapes (e.g.
   *  mixed ref+long 2-param). The generated class `implements` this interface
   *  and exposes a primitive `apply` method so JitRuntime can dispatch without
   *  boxing. */
  private def determineInterface(n: Int, paramIsRef: Array[Boolean], isDouble: Boolean): String | Null =
    val pkg = "scalascript.interpreter.vm.jit"
    if n == 1 then
      if paramIsRef(0) then if isDouble then s"$pkg.ObjToDouble" else s"$pkg.ObjToLong"
      else if isDouble then s"$pkg.DoubleFn1" else s"$pkg.LongFn1"
    else if n == 2 then
      (paramIsRef(0), paramIsRef(1)) match
        case (false, false) =>
          if isDouble then s"$pkg.DoubleFn2"   else s"$pkg.LongFn2"
        case (false, true)  =>
          if isDouble then s"$pkg.LongObjToDouble" else s"$pkg.LongObjToLong"
        case (true,  false) =>
          if isDouble then s"$pkg.ObjLongToDouble" else s"$pkg.ObjLongToLong"
        case (true,  true)  => null  // both-ref not yet wired; rare shape
    else null

  private def doCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): JitResult | Null =
    if isBoolReturning(f.body) then return null
    val paramSet = f.params.toSet
    // Per-param ref/int classification. A param is ref when it is the
    // scrutinee of a `Term.Match` body (the top-level match shape) — the only
    // ref-typed case the initial slice supports. A param read in arithmetic
    // (the pure-int subset) is int.
    val paramIsRef = new Array[Boolean](f.params.length)
    // Walk the body to find every Term.Match whose scrutinee is a parameter
    // name; mark that param as ref. Covers both the top-level
    // `def f(x) = x match { … }` shape (handled by walkMatchBody) and the
    // nested-as-expression shape `def f(x) = 1 + (x match { … })` that
    // walkMatchExpr handles. Without this walk, the nested case would compile
    // the function with a `long` param, then the InstanceV cast in walkMatchExpr
    // would be type-incompatible with the param type and javac would bail.
    def markRefScrutinees(t: scala.meta.Tree): Unit = t match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name =>
            val idx = f.params.indexOf(n.value)
            if idx >= 0 then paramIsRef(idx) = true
          case _ =>
        markRefScrutinees(tm.expr)
        tm.casesBlock.cases.foreach(c => markRefScrutinees(c.body))
      case _ => t.children.foreach(markRefScrutinees)
    markRefScrutinees(f.body)
    // For ref-param match functions (ADT match → result) also treat the body as
    // Double when it contains any Lit.Double — `walkMatchBody` will call
    // `walkDouble` per arm body, which propagates auto-promotion correctly.
    // The old `!paramIsRef.exists(identity)` guard blocked this path; removing
    // it lets `area(s: Shape): Double` compile to `static double area(Object s)`.
    val isDouble = bodyHasDoubleLit(f.body)
    val ctx = new GenCtx(f.name, paramSet, f.params.toArray, paramIsRef, isDouble, Map.empty, interp)
    // Try ref-returning (ObjToObject) path first for 1-param ref-scrutinee matches:
    // `def g(x: T): T = x match { case C(a, b) => a }` where the arm body names
    // a ref-typed binding.  `walkRefMatchBody` returns null whenever arm bodies
    // are Long-typed (numeric binding or Long expression), naturally gating this
    // path to definitively ref-returning functions.
    if f.params.length == 1 && paramIsRef(0) then
      f.body match
        case tm: Term.Match =>
          val refStmts = walkRefMatchBody(tm, ctx, interp)
          if refStmts != null then
            val className = s"GenJit_${sanitize(f.name)}_${System.identityHashCode(f.body)}"
            val pname = sanitize(f.params(0))
            val ifaceRef = "scalascript.interpreter.vm.jit.ObjToObject"
            val source =
              s"""public class $className implements $ifaceRef {
                 |  public static Object ${sanitize(f.name)}(Object $pname) {
                 |    $refStmts
                 |  }
                 |  public Object apply(Object n) { return ${sanitize(f.name)}(n); }
                 |}
                 |""".stripMargin
            val r = compileAndLink(className, sanitize(f.name), source, paramIsRef, classOf[Object], isDouble = false)
            if r != null then return r
        case _ =>
    val bodyStmts =
      f.body match
        case tm: Term.Match if paramIsRef.exists(identity) =>
          walkMatchBody(tm, ctx, interp)
        case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
          // Direction A.5: multi-stat block — emit val-bindings as Java locals,
          // then return the final expression. Special case: when the final stat
          // is a param-ref match, use walkMatchBody for it (preserves the
          // switch-statement form instead of wrapping in an IIFE).
          b.stats.last match
            case tm: Term.Match if paramIsRef.exists(identity) =>
              walkBlockStmts(b.stats.init, ctx) match
                case null  => null
                case prefix =>
                  val tailCtx = blockStmtsCtx(b.stats.init, ctx)
                  if tailCtx == null then null
                  else
                    val matchPart = walkMatchBody(tm, tailCtx, interp)
                    if matchPart == null then null else prefix + matchPart
            case _ =>
              walkBlockStmts(b.stats, ctx)
        case other =>
          val tco = tryTcoBody(other.asInstanceOf[Term], ctx)
          if tco != null then tco
          else if isDouble then
            val e = walkDouble(other.asInstanceOf[Term], ctx)
            if e == null then null else s"return $e;"
          else
            val e = walkLong(other.asInstanceOf[Term], ctx)
            if e == null then null else s"return $e;"
    if bodyStmts == null then return null

    val className = s"GenJit_${sanitize(f.name)}_${System.identityHashCode(f.body)}"
    val params = f.params.zipWithIndex.map { case (p, i) =>
      if paramIsRef(i) then s"Object ${sanitize(p)}"
      else if isDouble then s"double ${sanitize(p)}"
      else s"long ${sanitize(p)}"
    }.mkString(", ")
    val returnType = if isDouble then "double" else "long"
    val ifaceName = determineInterface(f.params.length, paramIsRef, isDouble)
    val implementsClause = if ifaceName != null then s" implements $ifaceName" else ""
    val argList = f.params.map(p => sanitize(p)).mkString(", ")
    val applyMethod =
      if ifaceName != null then
        s"\n  public $returnType apply($params) { return ${sanitize(f.name)}($argList); }"
      else ""
    val source =
      s"""public class $className$implementsClause {
         |  public static $returnType ${sanitize(f.name)}($params) {
         |    $bodyStmts
         |  }$applyMethod
         |}
         |""".stripMargin
    compileAndLink(className, sanitize(f.name), source, paramIsRef, if isDouble then classOf[Double] else classOf[Long], isDouble)

  /** Compile a Java source string in-memory, load the class, bind a MethodHandle
   *  to the static method, and instantiate the class as a typed direct interface.
   *  Used by both the Long/Double-returning path and the ObjToObject path. */
  private def compileAndLink(
    className:  String,
    methodName: String,
    source:     String,
    paramIsRef: Array[Boolean],
    rtype:      Class[?],
    isDouble:   Boolean
  ): JitResult | Null =
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then return null

    val classBytes = new ByteArrayOutputStream()
    val javaFile = new SimpleJavaFileObject(URI.create(s"string:///$className.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source

    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = classBytes

    val task = compiler.getTask(null, fm, null, null, null, java.util.Arrays.asList(javaFile))
    val ok =
      try task.call().booleanValue()
      catch case _: Throwable => false
    if !ok then return null

    val loader = new ClassLoader(classOf[JavacJitBackend.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls =
      try loader.loadClass(className)
      catch case _: Throwable => return null

    val ptypes: Array[Class[?]] = paramIsRef.map(isRef =>
      if isRef then classOf[Object]
      else if isDouble then classOf[Double]
      else classOf[Long]
    )
    val mt = MethodType.methodType(rtype, ptypes.asInstanceOf[Array[Class[?]]])
    val mh =
      try MethodHandles.lookup().findStatic(cls, methodName, mt)
      catch case _: Throwable => return null
    val direct: AnyRef | Null =
      try cls.getConstructor().newInstance().asInstanceOf[AnyRef]
      catch case _: Throwable => null
    new JitResult(mh, paramIsRef, isDouble, direct)

  // ── AST → Java source walker ──────────────────────────────────────────────

  /** Walker context: function name + params + pattern bindings currently in
   *  scope. `bindings(name)` maps a pattern binding to `(javaVarName, isRef)`.
   *  Params shadowed by bindings (rare; doesn't happen in the bench shape)
   *  resolve to the binding. */
  private final class GenCtx(
    val funName:     String,
    val params:      Set[String],
    val paramNames:  Array[String],
    val paramIsRef:  Array[Boolean],
    val isDouble:    Boolean,
    val bindings:    Map[String, (String, Boolean)],
    val interp:      scalascript.interpreter.Interpreter
  ):
    def isRefName(n: String): Boolean =
      bindings.get(n) match
        case Some((_, r)) => r
        case None =>
          val idx = paramNames.indexOf(n)
          idx >= 0 && paramIsRef(idx)
    def resolveLocal(n: String): String | Null =
      bindings.get(n) match
        case Some((jvar, _)) => jvar
        case None =>
          if params.contains(n) then sanitize(n) else null
    def withBindings(more: Iterable[(String, (String, Boolean))]): GenCtx =
      new GenCtx(funName, params, paramNames, paramIsRef, isDouble, bindings ++ more, interp)

  private def walkLong(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Int(v)  => s"${v}L"
    case Lit.Long(v) => s"${v}L"
    // Direction A.1/A.5: single-stmt block unwraps; multi-stmt blocks use IIFE.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkLong(inner, ctx)
        case _           => null
    case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
      val stmts = walkBlockStmts(b.stats, ctx)
      if stmts == null then null
      else s"((java.util.function.LongSupplier)(() -> { $stmts })).getAsLong()"
    // Nested `Term.Match` in expression context (e.g. `total + (e match { … })`).
    // The function body's top-level match is emitted as statements by
    // `walkMatchBody`; here we need an expression form, so we delegate to
    // `walkMatchExpr` which wraps the switch in a `LongSupplier` IIFE.
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx, ctx.interp)
    case tn: Term.Name =>
      // Only int-typed names can be read into a Long expression. Ref-typed
      // names (param scrutinee, ref-classified bindings) cannot.
      if ctx.isRefName(tn.value) then null
      else
        val local = ctx.resolveLocal(tn.value)
        if local != null then local
        else
          // Free name → try a top-level Int global. For a `val` binding (in
          // `interp.valNames`) we inline the current value as a Java literal
          // — saves one HashMap lookup per call site, which is the dominant
          // overhead for recursionFibMul (`val mul = 7; def fibMul(n) = if n<=1
          // then n * mul else …`). For `var`s and untagged names we keep the
          // per-call `readGlobalLong` dispatch so reassignments are observed.
          ctx.interp.globals.getOrElse(tn.value, null) match
            case Value.IntV(v) if ctx.interp.valNames.contains(tn.value) =>
              s"${v}L"
            case _: Value.IntV =>
              s"""scalascript.interpreter.vm.jit.JitGlobals$$.MODULE$$.readGlobalLong("${escape(tn.value)}")"""
            case _ => null
    case ti: Term.If =>
      val c = walkBool(ti.cond, ctx); if c == null then return null
      val a = walkLong(ti.thenp, ctx); if a == null then return null
      val b = walkLong(ti.elsep, ctx); if b == null then return null
      s"(($c) ? ($a) : ($b))"
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      val opStr = op.value
      opStr match
        case "+" | "-" | "*" | "/" | "%" =>
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"(($l $opStr $r) ? 1L : 0L)"
        case _ => null
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == ctx.funName =>
          val args = ap.argClause.values
          if args.length != ctx.paramNames.length then return null
          val sb = new StringBuilder
          sb.append(sanitize(ctx.funName)).append('(')
          var i = 0
          var rem = args
          while rem.nonEmpty do
            if i > 0 then sb.append(", ")
            val argStr =
              if ctx.paramIsRef(i) then walkRef(rem.head, ctx)
              else walkLong(rem.head, ctx)
            if argStr == null then return null
            sb.append(argStr)
            i += 1
            rem = rem.tail
          sb.append(')').toString
        case _ => null
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkLong(arg, ctx); if a == null then return null
      s"(${op.value}$a)"
    case _ => null

  /** Emit a Java `Object`-typed expression. Only `Term.Name` (referencing a
   *  ref-classified name in scope) is supported in the initial slice. */
  private def walkRef(t: Term, ctx: GenCtx): String | Null = t match
    case tn: Term.Name if ctx.isRefName(tn.value) => ctx.resolveLocal(tn.value)
    // Direction A.1: 1-stmt block unwrap parallel to walkLong.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkRef(inner, ctx)
        case _           => null
    case _                                        => null

  private def walkBool(t: Term, ctx: GenCtx): String | Null = t match
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      val opStr = op.value
      opStr match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          // Use walkDouble for double-typed funcs, walkLong otherwise. Comparison
          // operands are typed-by-context the same as the rest of the body.
          val w: (Term, GenCtx) => String | Null = if ctx.isDouble then walkDouble else walkLong
          val l = w(lhs, ctx); if l == null then return null
          val r = w(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case "&&" | "||" =>
          val l = walkBool(lhs, ctx); if l == null then return null
          val r = walkBool(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case _ => null
    case _ => null

  /** Double-typed parallel of `walkLong`. Used when `ctx.isDouble` is true
   *  (the fn body contains a `Lit.Double` somewhere → Scala promotes all
   *  arith to Double). Emits Java `double` expressions throughout. */
  private def walkDouble(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Double(v) => v.toString.toDouble.toString
    case Lit.Int(v)    => s"((double) ${v}L)"
    case Lit.Long(v)   => s"((double) ${v}L)"
    // Direction A.1/A.5: 1-stmt block unwraps; multi-stmt blocks use IIFE.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkDouble(inner, ctx)
        case _           => null
    case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
      val stmts = walkBlockStmts(b.stats, ctx)
      if stmts == null then null
      else s"((java.util.function.DoubleSupplier)(() -> { $stmts })).getAsDouble()"
    // Nested `Term.Match` in Double-typed expression context; mirrors
    // walkLong's treatment via the DoubleSupplier IIFE.
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx, ctx.interp)
    case tn: Term.Name =>
      if ctx.isRefName(tn.value) then null
      else
        val local = ctx.resolveLocal(tn.value)
        if local != null then local
        else
          // Free name → resolve as a top-level `Double` global. For a `val`
          // binding inline the value as a Java literal (parallel to walkLong's
          // val-globals constant-folding) — targets `recursionFibMulD`'s
          // `val mul = 7.0` hot path. Var/untagged keeps `readGlobalDouble`.
          ctx.interp.globals.getOrElse(tn.value, null) match
            case Value.DoubleV(v) if ctx.interp.valNames.contains(tn.value) =>
              v.toString.toDouble.toString
            case _: Value.DoubleV =>
              s"""scalascript.interpreter.vm.jit.JitGlobals$$.MODULE$$.readGlobalDouble("${escape(tn.value)}")"""
            case _ => null
    case ti: Term.If =>
      val c = walkBool(ti.cond, ctx); if c == null then return null
      val a = walkDouble(ti.thenp, ctx); if a == null then return null
      val b = walkDouble(ti.elsep, ctx); if b == null then return null
      s"(($c) ? ($a) : ($b))"
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      val opStr = op.value
      opStr match
        case "+" | "-" | "*" | "/" | "%" =>
          val l = walkDouble(lhs, ctx); if l == null then return null
          val r = walkDouble(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          // Boolean-result comparison in a non-cond position → not Double.
          // Cast to double via ternary so the expression's type matches.
          val l = walkDouble(lhs, ctx); if l == null then return null
          val r = walkDouble(argClause.values.head, ctx); if r == null then return null
          s"(($l $opStr $r) ? 1.0 : 0.0)"
        case _ => null
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == ctx.funName =>
          val args = ap.argClause.values
          if args.length != ctx.paramNames.length then return null
          val sb = new StringBuilder
          sb.append(sanitize(ctx.funName)).append('(')
          var i = 0
          var rem = args
          while rem.nonEmpty do
            if i > 0 then sb.append(", ")
            val argStr =
              if ctx.paramIsRef(i) then walkRef(rem.head, ctx)
              else walkDouble(rem.head, ctx)
            if argStr == null then return null
            sb.append(argStr)
            i += 1
            rem = rem.tail
          sb.append(')').toString
        case _ => null
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkDouble(arg, ctx); if a == null then return null
      s"(${op.value}$a)"
    case _ => null

  /** Emit the body of a `Term.Match` over an `InstanceV` scrutinee.
   *
   *  When all constructor arms have a registered int tag in `interp.typeTagMap`,
   *  emits `switch(inst.typeTag())` — javac lowers `switch(int)` to a JVM
   *  `tableswitch` (O(1), no string ops). Falls back to `switch(inst.typeName())`
   *  (String hash+equals lookupswitch) when any arm's tag is unknown (0). */
  private def walkMatchBody(tm: Term.Match, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return null
    if !ctx.params.contains(scrutName) then return null
    val scrutJava = sanitize(scrutName)
    // Pre-resolve int tags for all arms so we can decide switch type upfront.
    val cases = tm.casesBlock.cases
    val armTags = new Array[Int](cases.length)
    var allTagged = true
    var ai = 0
    val casesArr = cases.toArray
    while ai < casesArr.length do
      val ctorNameOpt = casesArr(ai).pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(n) => Some(n)
          case _            => None
        case _ => None
      ctorNameOpt match
        case Some(cn) =>
          val tag = interp.typeTagMap.getOrElse(cn, 0)
          if tag == 0 then allTagged = false
          armTags(ai) = tag
        case None => allTagged = false
      ai += 1
    val sb = new StringBuilder
    sb.append(s"scalascript.interpreter.Value.InstanceV inst = (scalascript.interpreter.Value.InstanceV) $scrutJava;\n    ")
    // If any arm carries a guard, fall back to a sequential if-chain because a
    // Java switch can't re-dispatch on guard failure: when `case A(n) if n > 0`
    // fails its guard, execution must continue to the next arm rather than
    // falling through to the next switch case (which may have a different tag).
    val hasAnyGuard = casesArr.exists(_.cond.nonEmpty)
    if hasAnyGuard then
      var ci = 0
      var restList = cases
      while restList.nonEmpty do
        val arm = walkArmAsIfBranch(restList.head, ctx, interp, if allTagged then armTags(ci) else 0, allTagged)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail
        ci += 1
      if allTagged then
        sb.append("throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + inst.typeTag());")
      else
        sb.append("throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + inst.typeName());")
    else
      if allTagged then
        sb.append("switch (inst.typeTag()) {\n    ")
      else
        sb.append("String tn = inst.typeName();\n    ")
        sb.append("switch (tn) {\n    ")
      var ci = 0
      var restList = cases
      while restList.nonEmpty do
        val arm = walkArm(restList.head, ctx, interp, if allTagged then armTags(ci) else 0)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail
        ci += 1
      if allTagged then
        sb.append("  default: throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + inst.typeTag());\n    }")
      else
        sb.append("  default: throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + tn);\n    }")
    sb.toString

  /** Emit a `Term.Match` as a Java switch *expression* (Java 14+) — the
   *  expression-context parallel of `walkMatchBody`. Used by `walkLong` /
   *  `walkDouble` when a match is part of a larger expression (e.g.
   *  `total + (e match { … })` or `1 + (s match { … })`). The result string
   *  is a valid Java expression that yields `long` or `double` depending on
   *  `ctx.isDouble`; the inline `switch (…)` produces the value directly,
   *  no intermediate local needed.
   *
   *  Requires Java 14+ (we target JDK 21). Falls back to null (caller bails)
   *  when:
   *    - the scrutinee is not a single param Term.Name
   *    - any arm shape is unsupported (guards, non-Pat.Extract)
   *    - any arm body fails to compile via walkLong/walkDouble */
  private def walkMatchExpr(tm: Term.Match, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return null
    if !ctx.params.contains(scrutName) then return null
    val scrutJava = sanitize(scrutName)
    // Pre-resolve int tags (mirrors walkMatchBody).
    val cases = tm.casesBlock.cases
    val casesArr = cases.toArray
    val armTags = new Array[Int](casesArr.length)
    var allTagged = true
    var ai = 0
    while ai < casesArr.length do
      val ctorNameOpt = casesArr(ai).pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(n) => Some(n)
          case _            => None
        case _ => None
      ctorNameOpt match
        case Some(cn) =>
          val tag = interp.typeTagMap.getOrElse(cn, 0)
          if tag == 0 then allTagged = false
          armTags(ai) = tag
        case None => allTagged = false
      ai += 1
    val sb = new StringBuilder
    // We need `inst` accessible inside each arm. Wrap the switch expression
    // in a primitive-typed Supplier IIFE so the outer call site sees a plain
    // long/double — `java.util.function.{LongSupplier,DoubleSupplier}` avoid
    // the boxing that a `Function<T, Long>` would incur. HotSpot caches the
    // lambda via the LambdaMetafactory's stable callsite, so per-call cost is
    // a single virtual dispatch (~1 ns), small relative to the switch body.
    val supplierIface = if ctx.isDouble then "java.util.function.DoubleSupplier" else "java.util.function.LongSupplier"
    val getterMethod  = if ctx.isDouble then "getAsDouble"                       else "getAsLong"
    sb.append(s"(($supplierIface)(() -> {\n      ")
    sb.append(s"scalascript.interpreter.Value.InstanceV inst = (scalascript.interpreter.Value.InstanceV) $scrutJava;\n      ")
    sb.append(s"return ")
    if allTagged then
      sb.append("switch (inst.typeTag()) {\n      ")
    else
      sb.append("switch (inst.typeName()) {\n      ")
    var ci = 0
    var restList = cases
    while restList.nonEmpty do
      val arm = walkArmExpr(restList.head, ctx, interp, if allTagged then armTags(ci) else 0)
      if arm == null then return null
      sb.append(arm)
      restList = restList.tail
      ci += 1
    if allTagged then
      sb.append("  default -> { throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + inst.typeTag()); }\n      };\n    }))")
    else
      sb.append("  default -> { throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + inst.typeName()); }\n      };\n    }))")
    sb.append(s".$getterMethod()")
    sb.toString

  /** Expression-form parallel of `walkArm`: emits `case N -> { … yield …; }`
   *  arrow form for use inside a Java switch *expression*. The bindings and
   *  arm-body walking logic is the same as `walkArm`; only the framing
   *  differs (no `return`, must `yield`). */
  private def walkArmExpr(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int): String | Null =
    if c.cond.nonEmpty then return null
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n
          case _            => return null
        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx.funName)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a"
            bindingMap += (bindNames(k) -> (jvar, bindIsRef(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        if intTag > 0 then sb.append(s"      case $intTag -> {\n        ")
        else               sb.append(s"""      case "${escape(ctorName)}" -> {\n        """)
        val faVar = s"__fa_${sanitize(ctorName)}"
        if n > 0 then sb.append(s"scalascript.interpreter.Value[] $faVar = inst.fieldsArr();\n        ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val fname = fieldOrder(fi)
            val readExpr =
              if n > 0 then s"""$faVar != null ? $faVar[$fi] : inst.fields().apply("${escape(fname)}")"""
              else          s"""inst.fields().apply("${escape(fname)}")"""
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n        """)
            else if ctx.isDouble then
              val raw = s"_rf${fi}_${sanitize(ctorName)}"
              sb.append(s"""Object $raw = $readExpr;\n        """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.Value.DoubleV ? ((scalascript.interpreter.Value.DoubleV) $raw).v() : (double) ((scalascript.interpreter.Value.IntV) $raw).v();\n        """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.Value.IntV) ($readExpr)).v();\n        """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"yield $armBodyJava;\n      }\n    ")
        sb.toString
      case _ => null

  /** Emit a single match arm as an `if (tagCheck) { bindings; [if (guard) { ]return body;[ }] }`
   *  block. Used when any arm in the enclosing match has a guard — a Java switch
   *  can't re-dispatch on guard failure, so the whole match is lowered to an
   *  if-chain that naturally falls through to the next arm. */
  private def walkArmAsIfBranch(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int, allTagged: Boolean): String | Null =
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n
          case _            => return null
        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx.funName)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a"
            bindingMap += (bindNames(k) -> (jvar, bindIsRef(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        if allTagged && intTag > 0 then
          sb.append(s"if (inst.typeTag() == $intTag) {\n      ")
        else
          sb.append(s"""if ("${escape(ctorName)}".equals(inst.typeName())) {\n      """)
        val faVar = s"__fa_${sanitize(ctorName)}"
        if n > 0 then sb.append(s"scalascript.interpreter.Value[] $faVar = inst.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val fname = fieldOrder(fi)
            val readExpr =
              if n > 0 then s"""$faVar != null ? $faVar[$fi] : inst.fields().apply("${escape(fname)}")"""
              else          s"""inst.fields().apply("${escape(fname)}")"""
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n      """)
            else if ctx.isDouble then
              val raw = s"_rf${fi}_${sanitize(ctorName)}"
              sb.append(s"""Object $raw = $readExpr;\n      """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.Value.DoubleV ? ((scalascript.interpreter.Value.DoubleV) $raw).v() : (double) ((scalascript.interpreter.Value.IntV) $raw).v();\n      """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.Value.IntV) ($readExpr)).v();\n      """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        if c.cond.nonEmpty then
          val guardJava = walkBool(c.cond.get, newCtx)
          if guardJava == null then return null
          sb.append(s"if ($guardJava) { return $armBodyJava; }\n    ")
        else
          sb.append(s"return $armBodyJava;\n    ")
        sb.append("  }\n    ")
        sb.toString
      case _ => null

  /** `intTag > 0` means emit `case $intTag:` (int switch); `intTag == 0` means
   *  emit `case "CtorName":` (String switch fallback). */
  private def walkArm(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int): String | Null =
    if c.cond.nonEmpty then return null   // guards not supported in initial slice
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n
          case _            => return null
        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx.funName)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a"
            bindingMap += (bindNames(k) -> (jvar, bindIsRef(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        // Int-tag switch emits `case N:`, string switch emits `case "Ctor":`.
        if intTag > 0 then
          sb.append(s"""  case $intTag: {\n      """)
        else
          sb.append(s"""  case "${escape(ctorName)}": {\n      """)
        // Hoist the fieldsArr lookup once per arm. inst is already typed as InstanceV.
        val faVar = s"__fa_${sanitize(ctorName)}"
        if n > 0 then
          sb.append(s"scalascript.interpreter.Value[] $faVar = inst.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val fname = fieldOrder(fi)
            val readExpr =
              if n > 0 then
                s"""$faVar != null ? $faVar[$fi] : inst.fields().apply("${escape(fname)}")"""
              else
                s"""inst.fields().apply("${escape(fname)}")"""
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n      """)
            else if ctx.isDouble then
              // Flexible DoubleV/IntV extraction for double-returning functions.
              val raw = s"_rf${fi}_${sanitize(ctorName)}"
              sb.append(s"""Object $raw = $readExpr;\n      """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.Value.DoubleV ? ((scalascript.interpreter.Value.DoubleV) $raw).v() : (double) ((scalascript.interpreter.Value.IntV) $raw).v();\n      """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.Value.IntV) ($readExpr)).v();\n      """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"return $armBodyJava;\n      }\n    ")
        sb.toString
      case _ => null

  /** Emit a single match arm for a ref-returning match body (`ObjToObject`).
   *  Fields whose static type (from `interp.typeFieldTypes`) is a primitive
   *  ("Int"/"Long"/"Double"/"Boolean") are declared `long`; ADT/String fields
   *  are declared `Object`.  `walkRef` bails on numeric bindings, so
   *  `walkRefMatchBody` naturally only succeeds for ref-returning arms. */
  private def walkRefArm(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int): String | Null =
    if c.cond.nonEmpty then return null
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n
          case _            => return null
        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val fieldTypes = interp.typeFieldTypes.getOrElse(ctorName, Nil)
        def isPrimField(fi: Int): Boolean = fi < fieldTypes.length && (fieldTypes(fi) match
          case "Int" | "Long" | "Double" | "Boolean" => true
          case _                                      => false)
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            bindingMap += (bindNames(k) -> (sanitize(bindNames(k)) + "_a", !isPrimField(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        if intTag > 0 then sb.append(s"  case $intTag: {\n      ")
        else               sb.append(s"""  case "${escape(ctorName)}": {\n      """)
        val faVar = s"__fa_${sanitize(ctorName)}"
        if n > 0 then sb.append(s"scalascript.interpreter.Value[] $faVar = inst.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val fname = fieldOrder(fi)
            val readExpr =
              if n > 0 then s"""$faVar != null ? $faVar[$fi] : inst.fields().apply("${escape(fname)}")"""
              else          s"""inst.fields().apply("${escape(fname)}")"""
            if isRef then sb.append(s"Object $jvar = $readExpr;\n      ")
            else          sb.append(s"long $jvar = ((scalascript.interpreter.Value.IntV)($readExpr)).v();\n      ")
          fi += 1
        val armBodyJava = walkRef(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"return (Object)$armBodyJava;\n    }\n    ")
        sb.toString
      // Wildcard-bind arm `case x => x` — binds the whole scrutinee to x.
      // In the switch context, x = inst (already cast to InstanceV before the switch).
      // Becomes the `default:` case; only valid as the last arm.
      case pv: scala.meta.Pat.Var =>
        val xName = pv.name.value
        val xBind = sanitize(xName) + "_a"
        val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
        val armBodyJava = walkRef(c.body, newCtx)
        if armBodyJava == null then return null
        val sb = new StringBuilder
        sb.append(s"  default: {\n      Object $xBind = inst;\n      return (Object)$armBodyJava;\n    }\n    ")
        sb.toString
      case _ => null

  /** Like `walkMatchBody` but for `ObjToObject` (ref-returning) match bodies.
   *  Returns null if any arm is non-Extract, guarded, or if `walkRefArm` fails
   *  (e.g. arm body is a numeric binding — only Long-returning matches bail). */
  private def walkRefMatchBody(tm: Term.Match, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return null
    if !ctx.params.contains(scrutName) then return null
    val scrutJava = sanitize(scrutName)
    val cases    = tm.casesBlock.cases
    val casesArr = cases.toArray
    if casesArr.exists(_.cond.nonEmpty) then return null
    val armTags  = new Array[Int](casesArr.length)
    var allTagged = true
    var ai = 0
    while ai < casesArr.length do
      casesArr(ai).pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(cn) =>
            val tag = interp.typeTagMap.getOrElse(cn, 0)
            if tag == 0 then allTagged = false
            armTags(ai) = tag
          case _ => allTagged = false
        case _ => allTagged = false
      ai += 1
    val sb = new StringBuilder
    sb.append(s"scalascript.interpreter.Value.InstanceV inst = (scalascript.interpreter.Value.InstanceV) $scrutJava;\n    ")
    if allTagged then sb.append("switch (inst.typeTag()) {\n    ")
    else
      sb.append("String tn = inst.typeName();\n    ")
      sb.append("switch (tn) {\n    ")
    var ci = 0
    var restList = cases
    var hasWildcard = false
    while restList.nonEmpty do
      restList.head.pat match
        case _: scala.meta.Pat.Var => hasWildcard = true
        case _                     =>
      val arm = walkRefArm(restList.head, ctx, interp, if allTagged then armTags(ci) else 0)
      if arm == null then return null
      sb.append(arm)
      restList = restList.tail
      ci += 1
    if !hasWildcard then
      if allTagged then
        sb.append("  default: throw new RuntimeException(\"walkRefMatchBody: no case matched, tag=\" + inst.typeTag());\n    }")
      else
        sb.append("  default: throw new RuntimeException(\"walkRefMatchBody: no case matched, typeName=\" + tn);\n    }")
    else
      sb.append("}")
    sb.toString

  /** TCO body emission: `if cond then base else recur(args)` (or vice versa)
   *  with a self-call in tail position → Java `while (true) { ... }` loop.
   *  Eliminates the `tcoTrampoline` cost AND the JVM stack growth a naive
   *  recursive method-call emission would pay.
   *
   *  Args are evaluated through temp locals BEFORE param-slot updates so the
   *  new-arg computation reads the OLD param values (mirrors how the
   *  trampoline copies args via `TailCall.args` snapshot). */
  /** Direction A.5: Emit Java `long`/`double` local declarations for `Defn.Val`
   *  stats, followed by `return <expr>;` for the last `Term`.
   *  Returns null if any stat is not a simple `val n = <long-or-double-expr>`
   *  or the final term can't compile via walkLong/walkDouble.
   *  Only non-ref (Long/Double) val bindings supported in the initial slice. */
  private def walkBlockStmts(stats: List[Stat], ctx: GenCtx): String | Null =
    val sb = new StringBuilder
    var curCtx = ctx
    var rem = stats
    while rem.nonEmpty do
      val stat = rem.head
      rem = rem.tail
      if rem.isEmpty then
        stat match
          case last: Term =>
            val e = if curCtx.isDouble then walkDouble(last, curCtx)
                    else walkLong(last, curCtx)
            if e == null then return null
            sb.append(s"return $e;")
          case _ => return null
      else
        stat match
          case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
            val jn = sanitize(n.value)
            val e = if curCtx.isDouble then walkDouble(rhs, curCtx)
                    else walkLong(rhs, curCtx)
            if e == null then return null
            val jType = if curCtx.isDouble then "double" else "long"
            sb.append(s"$jType $jn = $e;\n      ")
            curCtx = curCtx.withBindings(Seq(n.value -> (jn, false)))
          case _ => return null
    sb.toString

  /** Returns the GenCtx after threading all non-final `val` bindings from
   *  `stats`, used to build the tail context for a block-with-match-end. */
  private def blockStmtsCtx(stats: List[Stat], ctx: GenCtx): GenCtx | Null =
    var curCtx = ctx
    var rem = stats
    while rem.nonEmpty do
      rem.head match
        case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
          val jn = sanitize(n.value)
          val e = if curCtx.isDouble then walkDouble(rhs, curCtx)
                  else walkLong(rhs, curCtx)
          if e == null then return null
          curCtx = curCtx.withBindings(Seq(n.value -> (jn, false)))
        case _ => return null
      rem = rem.tail
    curCtx

  private def tryTcoBody(t: Term, ctx: GenCtx): String | Null = t match
    case ti: Term.If =>
      val recArgsT = asSelfRecur(ti.thenp, ctx.funName)
      val recArgsE = asSelfRecur(ti.elsep, ctx.funName)
      (recArgsT, recArgsE) match
        case (Some(_), Some(_)) => null   // both branches recur — no base case visible here
        case (Some(args), None) =>
          // `if cond then recur else base` → loop while cond is true.
          emitTcoLoop(ti.cond, ti.elsep, args, ctx, condInvertsExit = true)
        case (None, Some(args)) =>
          // `if cond then base else recur` → loop while cond is false.
          emitTcoLoop(ti.cond, ti.thenp, args, ctx, condInvertsExit = false)
        case _ => null
    case _ => null

  private def asSelfRecur(t: Term, funName: String): Option[List[Term]] = t match
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == funName => Some(ap.argClause.values)
        case _                                    => None
    case _ => None

  /** Emit: `while (true) { if (<exitCond>) return <base>;
   *                        long _t0 = <newArg0>; …;
   *                        <p0> = _t0; …; }`.
   *
   *  `condInvertsExit = true` means the original `cond` selects the recur
   *  branch (so the exit condition is `!cond`). `false` means `cond` selects
   *  the base branch (exit is `cond` directly). */
  private def emitTcoLoop(cond: Term, baseExpr: Term, recurArgs: List[Term], ctx: GenCtx, condInvertsExit: Boolean): String | Null =
    if recurArgs.length != ctx.paramNames.length then return null
    val condJava = walkBool(cond, ctx); if condJava == null then return null
    val baseJava = walkLong(baseExpr, ctx); if baseJava == null then return null
    val argStrs = new Array[String](recurArgs.length)
    var i = 0
    var rem = recurArgs
    while rem.nonEmpty do
      val s =
        if ctx.paramIsRef(i) then walkRef(rem.head, ctx)
        else walkLong(rem.head, ctx)
      if s == null then return null
      argStrs(i) = s
      i += 1
      rem = rem.tail
    val sb = new StringBuilder
    sb.append("while (true) {\n      ")
    val exitCond = if condInvertsExit then s"!($condJava)" else condJava
    sb.append(s"if ($exitCond) return $baseJava;\n      ")
    var j = 0
    while j < argStrs.length do
      val tType = if ctx.paramIsRef(j) then "Object" else "long"
      sb.append(s"$tType _t$j = ${argStrs(j)};\n      ")
      j += 1
    j = 0
    while j < argStrs.length do
      sb.append(s"${sanitize(ctx.paramNames(j))} = _t$j;\n      ")
      j += 1
    sb.append("}")
    sb.toString

  /** True iff `bindingName` appears as a `Term.Name` arg in a
   *  `Term.Apply(Term.Name(funName), …)` anywhere in `armBody`. Used by
   *  `walkArm` to classify a pattern binding as ref (passed back into the
   *  self-recursive call → must be `Object` in Java) vs int (consumed only
   *  by arithmetic / returned as the int result). */
  private def bindingIsRef(armBody: Term, bindingName: String, funName: String): Boolean =
    var hit = false
    def walk(t: scala.meta.Tree): Unit =
      if hit then ()
      else t match
        case Term.Apply.After_4_6_0(Term.Name(`funName`), argClause) =>
          argClause.values.foreach {
            case Term.Name(n) if n == bindingName => hit = true
            case other                            => walk(other)
          }
        case _ => t.children.foreach(walk)
    walk(armBody)
    hit

  // ── while-loop JIT ───────────────────────────────────────────────────────

  /** Try to compile a `tryLongWhileAssign`-shaped while loop to Java bytecode.
   *
   *  Generates `static void run(long[] v)`: the hot loop reads/writes slots via
   *  `v[idx]`, runs until condition is false, returns with final values in `v`.
   *  Temp variables `t0, t1, …` capture all RHS expressions before updating
   *  slots, preserving cross-assign ordering.
   *
   *  Supported subset (intentionally narrow for v1 — `arithLoop` class):
   *  - Condition: `Term.ApplyInfix` with `<`, `<=`, `>`, `>=`, `==`, `!=`,
   *    `&&`, `||`; operands must be slot names or integer literals.
   *  - Body RHS: `Lit.Int`/`Lit.Long`, slot `Term.Name`, `Term.ApplyInfix`
   *    with `+`, `-`, `*`, `/`, `%` (Long arithmetic, no free names).
   *
   *  Free names (globals) bail — they'd require `withInterp` TLS and
   *  `readGlobalLong` callbacks. `pureCallSum`-class benches can be handled
   *  in a follow-up slice after benchmarking the base win. */
  /** Global cache: `Term` (condition node identity) → compiled `Method` or
   *  `WhileLongMiss`. Keyed by object identity so the same AST node across
   *  multiple `Interpreter` instances (e.g. repeated JMH benchmark iterations
   *  sharing the same pre-parsed `Module`) hits the cache instead of running
   *  javac every time. */
  private val whileLongGlobalCache: java.util.IdentityHashMap[Term, AnyRef] =
    java.util.IdentityHashMap()
  private val WhileLongMiss: AnyRef = new AnyRef

  /** Per-tryCompileWhileLong walker context: holds the slot name list,
   *  optional `interp` for resolving callees, and an ordered emission map
   *  of `pureFnEmissions` — sanitised-fn-name → Java method source. Each
   *  unique pure top-level def referenced from a body RHS gets co-emitted
   *  as a static method in the generated class; the body emits
   *  `<sanitisedName>(<arg>)` instead of bailing. Helps `pureCallSum` /
   *  `pureCallSum2` (the 13-14 ms per-iter eval dispatch).
   *
   *  `refNames` / `refFnNames` / `refObjFnNames` accumulate lazily as
   *  `walkLocalSlotCtx` encounters InstanceV globals, ObjToLong fn calls,
   *  and ObjToObject-chained ref args. */
  private final class WhileGenCtx(
    val slots:           Array[String],
    val interp:          scalascript.interpreter.Interpreter | Null,
    val pureFnEmissions: scala.collection.mutable.LinkedHashMap[String, String],
    val refNames:        scala.collection.mutable.ArrayBuffer[String] =
                           scala.collection.mutable.ArrayBuffer.empty,
    val refFnNames:      scala.collection.mutable.ArrayBuffer[String] =
                           scala.collection.mutable.ArrayBuffer.empty,
    val refObjFnNames:   scala.collection.mutable.ArrayBuffer[String] =
                           scala.collection.mutable.ArrayBuffer.empty,
    // true when this ctx is for an inlined callee body — the callee method is
    // co-emitted as a static method without a ref preamble, so ObjToLong ref
    // calls cannot be used inside it.
    val isCallee:        Boolean = false
  ):
    def refIdx(name: String): Int =
      val i = refNames.indexOf(name)
      if i >= 0 then i
      else { refNames += name; refNames.length - 1 }

    def refFnIdx(name: String): Int =
      val i = refFnNames.indexOf(name)
      if i >= 0 then i
      else { refFnNames += name; refFnNames.length - 1 }

    def refObjFnIdx(name: String): Int =
      val i = refObjFnNames.indexOf(name)
      if i >= 0 then i
      else { refObjFnNames += name; refObjFnNames.length - 1 }

  override def tryCompileWhileLong(
    cond:  Term,
    names: Array[String],
    rhs:   Array[Term],
    interp: scalascript.interpreter.Interpreter | Null = null
  ): WhileJitEntry | Null =
    if !enabled then return null
    // Global cache: avoid javac on every Interpreter instance / benchmark iter.
    val globalCached = whileLongGlobalCache.get(cond)
    if globalCached eq WhileLongMiss then return null
    if globalCached != null then return globalCached.asInstanceOf[WhileJitEntry]
    val ctx = new WhileGenCtx(
      names,
      interp,
      scala.collection.mutable.LinkedHashMap.empty[String, String]
    )
    val condJava = walkLocalBoolCtx(cond, ctx)
    if condJava == null then return null
    val rhsJava = new Array[String](names.length)
    var k = 0
    while k < names.length do
      rhsJava(k) = walkLocalSlotCtx(rhs(k), ctx)
      if rhsJava(k) == null then return null
      k += 1
    val className = s"WhileLong_${Integer.toUnsignedString(System.identityHashCode(cond))}"
    val sb = new StringBuilder
    sb.append(s"public final class $className {\n")
    sb.append(s"  public static void run(long[] v) {\n")
    // Ref preamble: if any ObjToLong fn calls were emitted, load the fn and
    // ref arrays from TLS once before the loop and extract to locals so the
    // JVM can treat them as de-facto constants after the first iteration.
    val jitPkg = "scalascript.interpreter.vm.jit"
    val nRefFns = ctx.refFnNames.length
    if nRefFns > 0 then
      sb.append(s"    $jitPkg.ObjToLong[] _fn = $jitPkg.JitGlobals.getRefFns();\n")
      var ri = 0
      while ri < nRefFns do
        sb.append(s"    $jitPkg.ObjToLong _fn$ri = _fn[$ri];\n")
        ri += 1
    val nRefObjFns = ctx.refObjFnNames.length
    if nRefObjFns > 0 then
      sb.append(s"    $jitPkg.ObjToObject[] _objFn = $jitPkg.JitGlobals.getRefObjFns();\n")
      var ri = 0
      while ri < nRefObjFns do
        sb.append(s"    $jitPkg.ObjToObject _objFn$ri = _objFn[$ri];\n")
        ri += 1
    val nRefs = ctx.refNames.length
    if nRefs > 0 then
      sb.append(s"    Object[] _r = $jitPkg.JitGlobals.getRefs();\n")
      var ri = 0
      while ri < nRefs do
        sb.append(s"    Object _r$ri = _r[$ri];\n")
        ri += 1
    // Copy long array to named locals so the JVM can register-allocate them.
    // Sequential semantics: each assign sees the updated value of prior
    // assigns in the same iteration (matches tryLongWhileAssign behaviour).
    var i = 0
    while i < names.length do
      sb.append(s"    long _v$i = v[$i];\n")
      i += 1
    sb.append(s"    while ($condJava) {\n")
    i = 0
    while i < names.length do
      sb.append(s"      _v$i = ${rhsJava(i)};\n")
      i += 1
    sb.append("    }\n")
    i = 0
    while i < names.length do
      sb.append(s"    v[$i] = _v$i;\n")
      i += 1
    sb.append("  }\n")
    // Co-emit each unique pure-fn callee as a static method in the same class.
    // walkLocalSlotCtx populates `ctx.pureFnEmissions` lazily during the body
    // walk above; we append the method sources after `run` so the class is
    // self-contained (no cross-class linking required).
    val emissionIt = ctx.pureFnEmissions.valuesIterator
    while emissionIt.hasNext do
      sb.append(emissionIt.next())
    sb.append("}\n")
    val source = sb.toString
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then
      whileLongGlobalCache.put(cond, WhileLongMiss)
      return null
    val classBytes = new ByteArrayOutputStream()
    val javaFile = new SimpleJavaFileObject(URI.create(s"string:///$className.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = classBytes
    val task = compiler.getTask(null, fm, null, null, null, java.util.Arrays.asList(javaFile))
    val ok = try task.call().booleanValue() catch case _: Throwable => false
    if !ok then
      whileLongGlobalCache.put(cond, WhileLongMiss)
      return null
    val loader = new ClassLoader(classOf[JavacJitBackend.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls = try loader.loadClass(className) catch case _: Throwable =>
      whileLongGlobalCache.put(cond, WhileLongMiss)
      return null
    val method =
      try
        val m = cls.getMethod("run", classOf[Array[Long]])
        m.setAccessible(true)
        m
      catch case _: Throwable => null
    if method == null then
      whileLongGlobalCache.put(cond, WhileLongMiss)
      return null
    // Resolve ObjToLong and ObjToObject fn instances now (compile-time interp
    // available). Cached in `whileLongGlobalCache`; re-resolving per call
    // would add a globals lookup per loop invocation.
    val refFnsArr: Array[ObjToLong] =
      if ctx.refFnNames.isEmpty || interp == null then Array.empty[ObjToLong]
      else
        val arr = new Array[ObjToLong](ctx.refFnNames.length)
        var fi = 0
        var ok2 = true
        while fi < ctx.refFnNames.length && ok2 do
          val fnV = interp.globals.getOrElse(ctx.refFnNames(fi), null)
          if !fnV.isInstanceOf[Value.FunV] then ok2 = false
          else
            val jitR = tryCompile(fnV.asInstanceOf[Value.FunV], interp)
            if jitR == null || jitR.direct == null || !jitR.direct.isInstanceOf[ObjToLong] then ok2 = false
            else arr(fi) = jitR.direct.asInstanceOf[ObjToLong]
          fi += 1
        if !ok2 then
          whileLongGlobalCache.put(cond, WhileLongMiss)
          return null
        arr
    val refObjFnsArr: Array[ObjToObject] =
      if ctx.refObjFnNames.isEmpty || interp == null then Array.empty[ObjToObject]
      else
        val arr = new Array[ObjToObject](ctx.refObjFnNames.length)
        var fi = 0
        var ok2 = true
        while fi < ctx.refObjFnNames.length && ok2 do
          val fnV = interp.globals.getOrElse(ctx.refObjFnNames(fi), null)
          if !fnV.isInstanceOf[Value.FunV] then ok2 = false
          else
            val jitR = tryCompile(fnV.asInstanceOf[Value.FunV], interp)
            if jitR == null || jitR.direct == null || !jitR.direct.isInstanceOf[ObjToObject] then ok2 = false
            else arr(fi) = jitR.direct.asInstanceOf[ObjToObject]
          fi += 1
        if !ok2 then
          whileLongGlobalCache.put(cond, WhileLongMiss)
          return null
        arr
    val entry = new WhileJitEntry(
      method,
      if ctx.refNames.isEmpty then Array.empty[String] else ctx.refNames.toArray,
      refFnsArr,
      refObjFnsArr
    )
    whileLongGlobalCache.put(cond, entry.asInstanceOf[AnyRef])
    entry

  /** Walk a Term to a Java `long` expression using `_v$idx` local variables
   *  (as declared in the generated method prologue). Supports:
   *  `Lit.Int`/`Lit.Long`, slot `Term.Name`, `Term.ApplyInfix` with
   *  `{+, -, *, /, %}`, and (when `ctx.interp` is non-null) `Term.Apply`
   *  to a pure top-level def whose body is itself walkable via this
   *  function — the callee is co-emitted as a static method in the
   *  generated class (Direction A.3 of the perf roadmap). Returns null
   *  for anything outside this subset — caller bails to LExpr path. */
  private def walkLocalSlotCtx(t: Term, ctx: WhileGenCtx): String | Null = t match
    case Lit.Int(v)  => s"${v}L"
    case Lit.Long(v) => s"${v}L"
    // Direction A.1: 1-stmt blocks (`{ expr }`) unwrap transparently.
    // Multi-stmt blocks (let-bindings + sequences) bail until A.5 lands.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkLocalSlotCtx(inner, ctx)
        case _           => null
    case tn: Term.Name =>
      var idx = 0
      while idx < ctx.slots.length do
        if ctx.slots(idx) == tn.value then return s"_v$idx"
        idx += 1
      null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" =>
          val l = walkLocalSlotCtx(lhs, ctx); if l == null then return null
          val r = walkLocalSlotCtx(argClause.values.head, ctx); if r == null then return null
          s"($l ${op.value} $r)"
        case _ => null
    // Direction A.2 companion: unary `-x` / `+x` emit a Java prefix op so
    // callees like `def absIf(x) = if x < 0 then -x else x` compile cleanly.
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkLocalSlotCtx(arg, ctx); if a == null then return null
      s"(${op.value}$a)"
    // Direction A.2: ternary emission for `if cond then thenExpr else elseExpr`.
    // Cond walked via walkLocalBoolCtx (which handles cmp + && + ||); branches
    // walked via this same slot walker — so callees with `if … then a else b`
    // bodies and while-loops with `x = if cond then … else …` RHSes both
    // compile to a single Java conditional expression.
    case ti: Term.If =>
      val c = walkLocalBoolCtx(ti.cond, ctx); if c == null then return null
      val a = walkLocalSlotCtx(ti.thenp, ctx); if a == null then return null
      val b = walkLocalSlotCtx(ti.elsep, ctx); if b == null then return null
      s"(($c) ? ($a) : ($b))"
    case ap: Term.Apply =>
      ap.fun match
        case fnName: Term.Name if ctx.interp != null =>
          val nargs = ap.argClause.values.length
          if (nargs != 1 && nargs != 2) then return null
          val args = ap.argClause.values
          // ObjToLong fast path: single arg that resolves to a val-bound
          // InstanceV global → function must be JIT-compiled as ObjToLong.
          // The arg is emitted as `_rN` (loaded from JitGlobals.getRefs()
          // before the loop); the call is `_fnM.apply(_rN)`.
          // We MUST take this branch (not fall through to the pure-long path)
          // when the arg is a ref: passing a ref as `long _v0` would be a
          // Java type error.
          // Only valid at the outer-loop level (not inside a co-emitted callee
          // static method): callee methods have no ref preamble.
          if nargs == 1 && !ctx.isCallee then
            val argRefJava = walkRefArgCtx(args.head, ctx)
            if argRefJava != null then
              val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
              if !fnV.isInstanceOf[Value.FunV] then return null
              val fn = fnV.asInstanceOf[Value.FunV]
              if fn.params.length != 1 then return null
              val jitR = tryCompile(fn, ctx.interp)
              if jitR == null || !jitR.paramIsRef(0) || jitR.resultIsDouble || jitR.direct == null ||
                 !jitR.direct.isInstanceOf[ObjToLong]
              then return null
              val fi = ctx.refFnIdx(fnName.value)
              return s"_fn$fi.apply($argRefJava)"
          val sanitised = pureFnMethodName(fnName.value)
          // Pure-long path: resolve callee → check it's a pure FunV of the
          // right arity → ensure (or emit) its co-compiled method in this class.
          val a0 = walkLocalSlotCtx(args.head, ctx); if a0 == null then return null
          val a1 =
            if nargs == 2 then
              val r = walkLocalSlotCtx(args(1), ctx); if r == null then return null else r
            else ""
          if ctx.pureFnEmissions.contains(sanitised) then
            if nargs == 1 then s"$sanitised($a0)"
            else                s"$sanitised($a0, $a1)"
          else
            val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
            if (fnV eq null) || !fnV.isInstanceOf[Value.FunV] then return null
            val fn = fnV.asInstanceOf[Value.FunV]
            if fn.params.length != nargs then return null
            if fn.usingParams.nonEmpty || fn.returnsThrows then return null
            if fn.defaults.nonEmpty && fn.defaults.exists(_.nonEmpty) then return null
            if fn.paramTypes.nonEmpty && fn.paramTypes.exists(_.endsWith("*")) then return null
            // Walk the callee body with the callee's params as the slot list —
            // walkLocalSlotCtx then emits param refs as `_v0` / `_v1`.
            // isCallee=true: the co-emitted static method has no ref preamble.
            val calleeCtx = new WhileGenCtx(
              fn.params.toArray, ctx.interp, ctx.pureFnEmissions,
              isCallee = true
            )
            val bodyJava = walkLocalSlotCtx(fn.body, calleeCtx); if bodyJava == null then return null
            val params =
              if nargs == 1 then "long _v0"
              else                "long _v0, long _v1"
            val methodSrc =
              s"  public static long $sanitised($params) {\n    return $bodyJava;\n  }\n"
            ctx.pureFnEmissions(sanitised) = methodSrc
            if nargs == 1 then s"$sanitised($a0)"
            else                s"$sanitised($a0, $a1)"
        case _ => null
    case _ => null

  /** Resolve a Term to a Java ref expression (`_rN`, `_objFnN.apply(_rM)`,
   *  etc.), or null if the term cannot be used as a ref arg.
   *
   *  Supported forms:
   *  - `Term.Name(n)` — val-bound InstanceV global → `"_r$i"`.
   *  - `Term.Select(Term.Name(n), field)` — InstanceV field access →
   *    `"_r$i"` where the ref slot key is `"n.field"`.  At invocation time
   *    `EvalRuntime.tryWhileJit` resolves dotted keys via a two-level lookup.
   *  - `Term.Apply(fn, [refArg])` — single-arg `ObjToObject`-compiled fn →
   *    `"_objFn$j.apply($innerRef)"`.  Registered in `ctx.refObjFnNames`.
   *    Only valid at outer-loop level (not inside a callee static method). */
  private def walkRefArgCtx(t: Term, ctx: WhileGenCtx): String | Null = t match
    case tn: Term.Name if ctx.interp != null =>
      // If the name is already a long slot, it's not a ref.
      var si = 0
      while si < ctx.slots.length do
        if ctx.slots(si) == tn.value then return null
        si += 1
      ctx.interp.globals.getOrElse(tn.value, null) match
        case _: Value.InstanceV =>
          val ri = ctx.refIdx(tn.value)
          s"_r$ri"
        case _ => null
    case ts: Term.Select if ctx.interp != null =>
      ts.qual match
        case qn: Term.Name =>
          ctx.interp.globals.getOrElse(qn.value, null) match
            case inst: Value.InstanceV =>
              val fieldName = ts.name.value
              inst.fields.getOrElse(fieldName, null) match
                case _: Value.InstanceV =>
                  val ri = ctx.refIdx(s"${qn.value}.$fieldName")
                  s"_r$ri"
                case _ => null
            case _ => null
        case _ => null
    case ap: Term.Apply if ctx.interp != null && !ctx.isCallee =>
      ap.fun match
        case fnName: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
          val innerRefJava = walkRefArgCtx(ap.argClause.values.head, ctx)
          if innerRefJava == null then return null
          val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
          if !fnV.isInstanceOf[Value.FunV] then return null
          val fn = fnV.asInstanceOf[Value.FunV]
          if fn.params.length != 1 then return null
          val jitR = tryCompile(fn, ctx.interp)
          if jitR == null || !jitR.paramIsRef(0) || jitR.resultIsDouble ||
             jitR.direct == null || !jitR.direct.isInstanceOf[ObjToObject]
          then return null
          val oi = ctx.refObjFnIdx(fnName.value)
          s"_objFn$oi.apply($innerRefJava)"
        case _ => null
    case _ => null

  /** Sanitise a fn name into a valid Java identifier with a `fn_` prefix
   *  to avoid collisions with the generated `run` method or Java keywords. */
  private def pureFnMethodName(name: String): String =
    val sb = new StringBuilder("fn_")
    var i = 0
    while i < name.length do
      val c = name.charAt(i)
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') || c == '_' then sb.append(c)
      else sb.append('_')
      i += 1
    sb.toString

  /** Walk a condition Term to a Java boolean expression for the while guard.
   *  Handles comparisons (`<`, `<=`, `>`, `>=`, `==`, `!=`) and `&&`/`||`
   *  over slot-name / literal operands (via `walkLocalSlotCtx`). */
  private def walkLocalBoolCtx(t: Term, ctx: WhileGenCtx): String | Null = t match
    // Direction A.1: 1-stmt block unwrap mirrors the slot walker.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkLocalBoolCtx(inner, ctx)
        case _           => null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val l = walkLocalSlotCtx(lhs, ctx); if l == null then return null
          val r = walkLocalSlotCtx(argClause.values.head, ctx); if r == null then return null
          s"($l ${op.value} $r)"
        case "&&" | "||" =>
          val l = walkLocalBoolCtx(lhs, ctx); if l == null then return null
          val r = walkLocalBoolCtx(argClause.values.head, ctx); if r == null then return null
          s"($l ${op.value} $r)"
        case _ => null
    case _ => null

  /** Escape a string for embedding in a Java string literal (only `"` and
   *  `\` need escaping for our pure ASCII identifier-shaped strings). */
  private def escape(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '"' || c == '\\' then sb.append('\\').append(c)
      else sb.append(c)
      i += 1
    sb.toString

  /** Java identifier sanitization. ScalaScript identifiers can contain `$` and
   *  unicode; the generated method/class name keeps only `[A-Za-z0-9_]`, with
   *  other chars replaced by `_`. */
  private def sanitize(name: String): String =
    val sb = new StringBuilder(name.length)
    var i = 0
    while i < name.length do
      val c = name.charAt(i)
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') || c == '_' then sb.append(c)
      else sb.append('_')
      i += 1
    sb.toString
