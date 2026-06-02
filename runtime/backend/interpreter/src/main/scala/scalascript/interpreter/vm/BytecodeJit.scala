package scalascript.interpreter.vm

import java.lang.invoke.{MethodHandles, MethodHandle, MethodType}
import javax.tools.{ToolProvider, JavaFileObject, SimpleJavaFileObject, ForwardingJavaFileManager, JavaFileManager, FileObject}
import java.io.{ByteArrayOutputStream, OutputStream}
import java.net.URI

import scala.meta.{Term, Lit, Pat}
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
object BytecodeJit:

  /** Default **ON**. Opt out via env `SSC_JIT_BYTECODE=off` or the parallel
   *  system property `-Dssc.jit.bytecode=off` (JMH forks — `-D` propagates
   *  through `-jvmArgsAppend`, env vars do not always). The off-switch
   *  matches the pattern set by `SSC_JIT` and `SSC_FASTTIER`. */
  val enabled: Boolean =
    !sys.env.get("SSC_JIT_BYTECODE").map(_.toLowerCase).contains("off") &&
      !sys.props.get("ssc.jit.bytecode").map(_.toLowerCase).contains("off")

  /** Compilation result. `paramIsRef(i)` is true when the i-th param is
   *  passed as an `Object` (an `InstanceV`); otherwise the param is `long`
   *  (the Int case) when `resultIsDouble=false`, or `double` (the all-double
   *  case) when `resultIsDouble=true`. `resultIsDouble` also drives the
   *  caller's IntV-vs-DoubleV result wrapping in `JitRuntime`.
   *
   *  `direct`, when non-null, is an instance of one of the `JitInterfaces`
   *  traits (LongFn1 / DoubleFn1 / ObjToLong / ObjToDouble / LongFn2 /
   *  DoubleFn2) whose `apply` method calls the JIT-generated static method
   *  directly with unboxed primitives, eliminating the Long.valueOf allocations
   *  that `MethodHandle.invoke` produces. `JitRuntime.invokeBytecode*` uses
   *  this when non-null; falls back to `mh` when null (mixed-param functions
   *  or functions where instantiation failed). */
  final class Result(
    val mh:             MethodHandle,
    val paramIsRef:     Array[Boolean],
    val resultIsDouble: Boolean = false,
    val direct:         AnyRef | Null = null
  )

  /** Per-thread interpreter handle for the free-name globals read path.
   *  `JitRuntime` sets this on each MH invocation; the generated Java code
   *  calls back into `readGlobalLong(name)` to resolve a free `Int` global
   *  by name. The lookup adds one HashMap miss + a type cast per read, but
   *  the rest of the function body still benefits from the bytecode-JIT'd
   *  hot path. */
  private val interpTls: ThreadLocal[scalascript.interpreter.Interpreter] =
    new ThreadLocal[scalascript.interpreter.Interpreter]()

  def withInterp[A](interp: scalascript.interpreter.Interpreter)(thunk: => A): A =
    val prev = interpTls.get()
    interpTls.set(interp)
    // Restore via `set(prev)` rather than `remove()` even when `prev == null`:
    // `remove()` deletes the per-thread ThreadLocalMap.Entry, and the next
    // outer call's `set(interp)` then re-allocates it. JFR profiling on
    // `recursiveEval` showed ~10 MB/op of `ThreadLocalMap$Entry` allocations
    // on this exact path — one Entry per outer bytecode-JIT invocation, of
    // which there are millions per script. Setting the slot to `null` leaves
    // the Entry intact with a null value; `readGlobalLong/Double` already
    // check for `interp == null`, so semantics are preserved. Per-thread
    // memory cost: ~32 bytes that never shrink — negligible.
    try thunk
    finally interpTls.set(prev)

  /** Called by generated Java code: read a top-level `Int` global by name and
   *  return its `Long` value. Throws `RuntimeException` if the name is
   *  missing or not an `IntV` — caller's MH invocation catches and falls
   *  back to the SscVm.exec / tree-walk path. */
  def readGlobalLong(name: String): Long =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("BytecodeJit.readGlobalLong: no interp in TLS")
    val v = interp.globals.getOrElse(name, null)
    v match
      case Value.IntV(x) => x
      case _             => throw new RuntimeException(s"BytecodeJit.readGlobalLong: '$name' not an Int")

  /** Double-globals parallel of `readGlobalLong`. Resolves to `DoubleV` only;
   *  an `IntV` value at runtime throws and the wrapping MH invocation falls
   *  back to the SscVm.exec / tree-walk path. The compile-time gate in
   *  `walkDouble` only emits this call when the current `interp.globals`
   *  resolves the name to `DoubleV`, so the runtime mismatch only occurs if
   *  the global is reassigned to a non-Double value between compile time and
   *  call time — a rare shape; safer to bail than to silently widen IntV. */
  def readGlobalDouble(name: String): Double =
    val interp = interpTls.get()
    if interp == null then throw new RuntimeException("BytecodeJit.readGlobalDouble: no interp in TLS")
    val v = interp.globals.getOrElse(name, null)
    v match
      case Value.DoubleV(x) => x
      case _                => throw new RuntimeException(s"BytecodeJit.readGlobalDouble: '$name' not a Double")

  /** Cache by FunV body AST identity. Value is a `Result` on hit or the
   *  `BailSentinel` on miss (so we don't re-attempt compilation for the same
   *  body). Synchronized via the cache monitor. */
  private val cache = new java.util.IdentityHashMap[scala.meta.Term, AnyRef]()
  private val BailSentinel: AnyRef = new AnyRef

  def tryCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): Result | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length != 1 && f.params.length != 2 then return null
    val body = f.body
    cache.synchronized {
      val cached = cache.get(body)
      if cached != null then
        return if cached eq BailSentinel then null else cached.asInstanceOf[Result]
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
   *  short-circuit). `BytecodeJit` returns `long` from every generated method
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
    val pkg = "scalascript.interpreter.vm"
    if n == 1 then
      if paramIsRef(0) then if isDouble then s"$pkg.ObjToDouble" else s"$pkg.ObjToLong"
      else if isDouble then s"$pkg.DoubleFn1" else s"$pkg.LongFn1"
    else if n == 2 && !paramIsRef(0) && !paramIsRef(1) then
      if isDouble then s"$pkg.DoubleFn2" else s"$pkg.LongFn2"
    else null

  private def doCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): Result | Null =
    if isBoolReturning(f.body) then return null
    val paramSet = f.params.toSet
    // Per-param ref/int classification. A param is ref when it is the
    // scrutinee of a `Term.Match` body (the top-level match shape) — the only
    // ref-typed case the initial slice supports. A param read in arithmetic
    // (the pure-int subset) is int.
    val paramIsRef = new Array[Boolean](f.params.length)
    f.body match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name =>
            val idx = f.params.indexOf(n.value)
            if idx >= 0 then paramIsRef(idx) = true
          case _ =>
      case _ =>
    val isDouble = !paramIsRef.exists(identity) && bodyHasDoubleLit(f.body)
    val ctx = new GenCtx(f.name, paramSet, f.params.toArray, paramIsRef, isDouble, Map.empty, interp)
    val bodyStmts =
      f.body match
        case tm: Term.Match if paramIsRef.exists(identity) =>
          walkMatchBody(tm, ctx, interp)
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

    val loader = new ClassLoader(classOf[BytecodeJit.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls =
      try loader.loadClass(className)
      catch case _: Throwable => return null

    val ptypes: Array[Class[?]] = paramIsRef.zipWithIndex.map { case (isRef, _) =>
      if isRef then classOf[Object]
      else if isDouble then classOf[Double]
      else classOf[Long]
    }
    val rtype: Class[?] = if isDouble then classOf[Double] else classOf[Long]
    val mt = MethodType.methodType(rtype, ptypes.asInstanceOf[Array[Class[?]]])
    val mh =
      try MethodHandles.lookup().findStatic(cls, sanitize(f.name), mt)
      catch case _: Throwable => return null
    val direct: AnyRef | Null =
      if ifaceName != null then
        try cls.getConstructor().newInstance().asInstanceOf[AnyRef]
        catch case _: Throwable => null
      else null
    new Result(mh, paramIsRef, isDouble, direct)

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
    case tn: Term.Name =>
      // Only int-typed names can be read into a Long expression. Ref-typed
      // names (param scrutinee, ref-classified bindings) cannot.
      if ctx.isRefName(tn.value) then null
      else
        val local = ctx.resolveLocal(tn.value)
        if local != null then local
        else
          // Free name → try a top-level Int global. The Java code reads
          // through `BytecodeJit.readGlobalLong(name)` which dereferences a
          // thread-local interpreter handle set by `JitRuntime`'s
          // bytecode entry points.
          ctx.interp.globals.getOrElse(tn.value, null) match
            case _: Value.IntV =>
              s"""scalascript.interpreter.vm.BytecodeJit$$.MODULE$$.readGlobalLong("${escape(tn.value)}")"""
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
    case _ => null

  /** Emit a Java `Object`-typed expression. Only `Term.Name` (referencing a
   *  ref-classified name in scope) is supported in the initial slice. */
  private def walkRef(t: Term, ctx: GenCtx): String | Null = t match
    case tn: Term.Name if ctx.isRefName(tn.value) => ctx.resolveLocal(tn.value)
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
    case tn: Term.Name =>
      if ctx.isRefName(tn.value) then null
      else
        val local = ctx.resolveLocal(tn.value)
        if local != null then local
        else
          // Free name → resolve as a top-level `Double` global through
          // `BytecodeJit.readGlobalDouble`. Parallel to `walkLong`'s Int
          // globals path. Only DoubleV is supported; IntV/other bail.
          ctx.interp.globals.getOrElse(tn.value, null) match
            case _: Value.DoubleV =>
              s"""scalascript.interpreter.vm.BytecodeJit$$.MODULE$$.readGlobalDouble("${escape(tn.value)}")"""
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
    case _ => null

  /** Emit the body of a `Term.Match` over an `InstanceV` scrutinee. The
   *  generated Java is a sequence of `if (tn.equals("Ctor")) { … return … }`
   *  arms ending in a throw, mirroring the `runValue` linear-scan dispatch
   *  without the per-arm Computation/Pure allocation. */
  private def walkMatchBody(tm: Term.Match, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return null
    if !ctx.params.contains(scrutName) then return null
    val scrutJava = sanitize(scrutName)
    val sb = new StringBuilder
    sb.append(s"scalascript.interpreter.Value.InstanceV inst = (scalascript.interpreter.Value.InstanceV) $scrutJava;\n    ")
    sb.append("String tn = inst.typeName();\n    ")
    var rest = tm.casesBlock.cases
    while rest.nonEmpty do
      val arm = walkArm(rest.head, ctx, interp)
      if arm == null then return null
      sb.append(arm)
      rest = rest.tail
    sb.append("throw new RuntimeException(\"BytecodeJit: no case matched, typeName=\" + tn);")
    sb.toString

  private def walkArm(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
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
        // Classify each binding by its usage in the arm body (ref if appears
        // as an arg to a self-recursive call to `funName`).
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
        sb.append(s"""if ("${ctorName}".equals(tn)) {\n      """)
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val fname = fieldOrder(fi)
            if isRef then
              sb.append(s"""Object $jvar = inst.fields().apply("${escape(fname)}");\n      """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.Value.IntV) inst.fields().apply("${escape(fname)}")).v();\n      """)
          fi += 1
        val armBodyJava = walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"return $armBodyJava;\n    }\n    ")
        sb.toString
      case _ => null

  /** TCO body emission: `if cond then base else recur(args)` (or vice versa)
   *  with a self-call in tail position → Java `while (true) { ... }` loop.
   *  Eliminates the `tcoTrampoline` cost AND the JVM stack growth a naive
   *  recursive method-call emission would pay.
   *
   *  Args are evaluated through temp locals BEFORE param-slot updates so the
   *  new-arg computation reads the OLD param values (mirrors how the
   *  trampoline copies args via `TailCall.args` snapshot). */
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
