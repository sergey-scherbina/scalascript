package scalascript.interpreter.vm.jit

import java.lang.invoke.{MethodHandles, MethodType}
import javax.tools.{ToolProvider, JavaFileObject, SimpleJavaFileObject, ForwardingJavaFileManager, JavaFileManager, FileObject}
import java.io.{ByteArrayOutputStream, OutputStream}
import java.net.URI

import scala.meta.{Term, Lit, Pat, Stat, Defn}
import scalascript.interpreter.Value
import scalascript.interpreter.vm.JitMissStats

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

  /** `-classpath` options for the runtime `javac` JIT. With `null` options javac
   *  resolves references using only `java.class.path` — the full classpath under
   *  `java -jar` (the fat jar) and sbt's *forked* tests, but NOT under sbt
   *  `runMain` / unforked runs, which use layered `URLClassLoader`s whose entries
   *  never appear on `java.class.path`. There the generated Java cannot resolve the
   *  runtime classes it references and the compile bails to tree-walk, leaving the
   *  JIT codegen UNTESTED. We additionally harvest the running classloader
   *  hierarchy's `URLClassLoader` URLs so the JIT compiles under sbt too. The four
   *  `tryCompile` entry points already `catch Throwable => null` (crash-safety), so
   *  any codegen bug newly surfaced by actually compiling bails harmlessly. */
  private[scalascript] lazy val jitClasspathOptions: java.util.List[String] =
    val entries = new java.util.LinkedHashSet[String]()
    val sep = java.io.File.pathSeparator
    val jcp = System.getProperty("java.class.path", "")
    if jcp != null && jcp.nonEmpty then
      jcp.split(java.util.regex.Pattern.quote(sep)).foreach(e => if e.nonEmpty then entries.add(e))
    var cl = classOf[JavacJitBackend.type].getClassLoader
    while cl != null do
      cl match
        case u: java.net.URLClassLoader =>
          u.getURLs.foreach { url =>
            try entries.add(new java.io.File(url.toURI).getPath)
            catch case _: Throwable => ()
          }
        case _ => ()
      cl = cl.getParent
    if entries.isEmpty then java.util.Collections.emptyList()
    else java.util.Arrays.asList("-classpath", String.join(sep, entries))

  /** Cache by FunV body AST identity. Value is a `JitResult` on hit or the
   *  `BailSentinel` on miss (so we don't re-attempt compilation for the same
   *  body). Synchronized via the cache monitor. */
  private val cache = new java.util.IdentityHashMap[scala.meta.Term, AnyRef]()
  private val BailSentinel: AnyRef = new AnyRef
  /** Marks a body whose `doCompile` is currently in flight. A re-entrant
   *  `tryCompile` for the SAME body (a self-recursive function reached through
   *  `walkLocalSlotCtx` → `tryCompile`, e.g. `while … s = s + fib(20)`) sees it
   *  and bails instead of recursing into `doCompile` again — which otherwise
   *  overflows the stack (caught by the guard below, but expensively). Replaced
   *  by the real `JitResult`/`BailSentinel` once `doCompile` returns. */
  private val InProgressSentinel: AnyRef = new AnyRef

  /** Builtin ADTs that ARE registered in `typeFieldOrder` (Option/Either/etc.)
   *  but have dedicated construction + HOF dispatch (OptionV/EitherV, flatMap/
   *  map/getOrElse). The generic case-class → InstanceV path (T3) must NOT
   *  intercept them, or e.g. `Some(x)` becomes an InstanceV and
   *  `JitHofDispatch.flatMapGlobalLong` rejects the receiver. */
  private val builtinAdtCtorNames: Set[String] =
    Set("Some", "None", "Right", "Left", "List", "Set", "Map", "Nil", "Vector", "Seq", "Range")

  override def tryCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): JitResult | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length > 3 then return null
    val body = f.body
    cache.synchronized {
      val cached = cache.get(body)
      if cached != null then
        // BailSentinel and InProgressSentinel both mean "do not JIT this call":
        // the latter breaks a re-entrant self-recursive compile (see below).
        return if (cached eq BailSentinel) || (cached eq InProgressSentinel) then null
               else cached.asInstanceOf[JitResult]
      // Mark in-flight BEFORE doCompile so a recursive tryCompile for the same
      // body bails (the in-progress branch above) instead of re-entering doCompile
      // and overflowing the stack.
      cache.put(body, InProgressSentinel)
    }
    // A codegen bug (e.g. a `walkLocalSlotCtx` recursion → StackOverflowError on
    // some shapes) must NEVER crash the user's program — it must bail to tree-walk.
    // The downstream javac/classload steps are already guarded; this guards the
    // source-generation itself. The InProgressSentinel above prevents the specific
    // self-recursive overflow; this catch remains a backstop for any other shape.
    val result =
      try doCompile(f, interp)
      catch case _: Throwable => null
    cache.synchronized {
      cache.put(body, if result == null then BailSentinel else result.asInstanceOf[AnyRef])
    }
    if result == null then
      val reasons = classifyBailReasons(f)
      val reason  = if reasons.nonEmpty then
        if reasons.lengthCompare(1) == 0 then reasons.head else JitBailReason.Compound(reasons)
      else JitBailReason.UnknownShape
      JitMissStats.record("javac", reason)
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

  /** Delegates to the shared `JitPredicates.isBoolReturning` so the lint and
   *  the backend use identical logic. See `JitPredicates` for the rationale. */
  private def isBoolReturning(t: Term): Boolean = JitPredicates.isBoolReturning(t)

  /** Returns the fully-qualified name of the JitInterface that matches the
   *  compiled function's signature, or null for unsupported shapes (e.g.
   *  mixed ref+long 2-param). The generated class `implements` this interface
   *  and exposes a primitive `apply` method so JitRuntime can dispatch without
   *  boxing. */
  private def determineInterface(n: Int, paramIsRef: Array[Boolean], isDouble: Boolean, resultIsRef: Boolean = false): String | Null =
    val pkg = "scalascript.interpreter.vm.jit"
    if n == 0 then
      if isDouble then s"$pkg.DoubleFn0" else s"$pkg.LongFn0"
    else if n == 1 then
      if resultIsRef then
        if paramIsRef(0) then s"$pkg.ObjToObject" else s"$pkg.LongToObject"
      else if paramIsRef(0) then if isDouble then s"$pkg.ObjToDouble" else s"$pkg.ObjToLong"
      else if isDouble then s"$pkg.DoubleFn1" else s"$pkg.LongFn1"
    else if n == 2 then
      (paramIsRef(0), paramIsRef(1)) match
        case (false, false) =>
          if isDouble then s"$pkg.DoubleFn2"   else s"$pkg.LongFn2"
        case (false, true)  =>
          if isDouble then s"$pkg.LongObjToDouble" else s"$pkg.LongObjToLong"
        case (true,  false) =>
          if isDouble then s"$pkg.ObjLongToDouble" else s"$pkg.ObjLongToLong"
        case (true,  true)  =>
          if resultIsRef then s"$pkg.ObjObjToObject"
          else if isDouble then s"$pkg.ObjObjToDouble" else s"$pkg.ObjObjToLong"
    else if n == 3 then
      (paramIsRef(0), paramIsRef(1), paramIsRef(2)) match
        case (false, false, false) => if isDouble then s"$pkg.DoubleFn3"           else s"$pkg.LongFn3"
        case (true,  false, false) => if isDouble then s"$pkg.ObjLongLongToDouble" else s"$pkg.ObjLongLongToLong"
        case (false, true,  false) => if isDouble then s"$pkg.LongObjLongToDouble" else s"$pkg.LongObjLongToLong"
        case (false, false, true)  => if isDouble then s"$pkg.LongLongObjToDouble" else s"$pkg.LongLongObjToLong"
        case (true,  true,  false) => if isDouble then s"$pkg.ObjObjLongToDouble"  else s"$pkg.ObjObjLongToLong"
        case (true,  false, true)  => if isDouble then s"$pkg.ObjLongObjToDouble"  else s"$pkg.ObjLongObjToLong"
        case (false, true,  true)  => if isDouble then s"$pkg.LongObjObjToDouble"  else s"$pkg.LongObjObjToLong"
        case (true,  true,  true)  => if isDouble then s"$pkg.ObjObjObjToDouble"   else s"$pkg.ObjObjObjToLong"
    else null

  private def doCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): JitResult | Null =
    // Bool-body functions are compiled with a 0/1 wrapper instead of bailing.
    // `isBoolReturning` is still used in `jitCompatibleSibling` to exclude
    // bool-returning siblings from co-emission (where their return type
    // would be incorrectly inferred as Long by sibling co-emitters).
    val paramSet = f.params.toSet
    val paramIsRef = classifyParamRefs(f)
    // For ref-param match functions (ADT match → result) also treat the body as
    // Double when it contains any Lit.Double — `walkMatchBody` will call
    // `walkDouble` per arm body, which propagates auto-promotion correctly.
    // The old `!paramIsRef.exists(identity)` guard blocked this path; removing
    // it lets `area(s: Shape): Double` compile to `static double area(Object s)`.
    val isDouble = bodyHasDoubleLit(f.body)
    val coEmit = new CoEmitState
    coEmit.signatures.put(f.name, MethodSig(f.params.toArray, paramIsRef, isDouble))
    coEmit.emitted.add(f.name)
    val ctx = new GenCtx(f.name, paramSet, f.params.toArray, paramIsRef, isDouble, Map.empty, interp, coEmit, f.paramTypes.toArray)
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
            val r = compileAndLink(className, sanitize(f.name), source, paramIsRef, classOf[Object], isDouble = false, resultIsRef = true)
            if r != null then return r
        case _ =>
    if f.params.length == 1 && !paramIsRef(0) && !isDouble then
      val statics = scala.collection.mutable.LinkedHashMap.empty[String, String]
      val objectExpr = walkObject(f.body.asInstanceOf[Term], ctx, statics)
      if objectExpr != null then
        val className = s"GenJit_${sanitize(f.name)}_${System.identityHashCode(f.body)}"
        val params = s"long ${sanitize(f.params.head)}"
        val ifaceName = determineInterface(f.params.length, paramIsRef, isDouble = false, resultIsRef = true)
        val source =
          s"""public class $className implements $ifaceName {
             |${statics.valuesIterator.mkString}
             |  @SuppressWarnings("unchecked")
             |  private static scalascript.interpreter.Value$$package$$Value$$InstanceV __inst(String typeName, int tag, Object[] fields, String[] names) {
             |    scalascript.interpreter.Value$$package$$Value$$InstanceV inst = new scalascript.interpreter.Value$$package$$Value$$InstanceV(
             |      typeName.intern(),
             |      (scala.collection.immutable.Map<String, Object>) (scala.collection.immutable.Map<?, ?>) scala.collection.immutable.Map$$.MODULE$$.empty()
             |    );
             |    inst.fieldsArr_$$eq(fields);
             |    inst.fieldNames_$$eq(names);
             |    inst.typeTag_$$eq(tag);
             |    return inst;
             |  }
             |  public static Object ${sanitize(f.name)}($params) {
             |    return $objectExpr;
             |  }
             |  public Object apply(long n) { return ${sanitize(f.name)}(n); }
             |}
             |""".stripMargin
        val r = compileAndLink(className, sanitize(f.name), source, paramIsRef, classOf[Object], isDouble = false, resultIsRef = true)
        if r != null then return r
    val bodyStmts = walkFunctionBody(f, ctx, interp)
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
         |  }
         |${ctx.coEmit.extraMethods.valuesIterator.mkString}$applyMethod
         |}
         |""".stripMargin
    compileAndLink(className, sanitize(f.name), source, paramIsRef, if isDouble then classOf[Double] else classOf[Long], isDouble,
      resultIsBool = isBoolReturning(f.body))

  /** Compile a Java source string in-memory, load the class, bind a MethodHandle
   *  to the static method, and instantiate the class as a typed direct interface.
   *  Used by both the Long/Double-returning path and the ObjToObject path. */
  private def compileAndLink(
    className:  String,
    methodName: String,
    source:     String,
    paramIsRef: Array[Boolean],
    rtype:      Class[?],
    isDouble:   Boolean,
    resultIsRef: Boolean = false,
    resultIsBool: Boolean = false
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

    val task = compiler.getTask(null, fm, null, jitClasspathOptions, null, java.util.Arrays.asList(javaFile))
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
    new JitResult(mh, paramIsRef, isDouble, direct, resultIsRef, resultIsBool)

  // ── AST → Java source walker ──────────────────────────────────────────────

  private final case class MethodSig(paramNames: Array[String], paramIsRef: Array[Boolean], isDouble: Boolean)

  private final class CoEmitState:
    val signatures:   scala.collection.mutable.HashMap[String, MethodSig] =
      scala.collection.mutable.HashMap.empty
    val extraMethods: scala.collection.mutable.LinkedHashMap[String, String] =
      scala.collection.mutable.LinkedHashMap.empty
    val emitting:     scala.collection.mutable.HashSet[String] =
      scala.collection.mutable.HashSet.empty
    val emitted:      scala.collection.mutable.HashSet[String] =
      scala.collection.mutable.HashSet.empty
    // Siblings co-emitted as `static String f(...)` (String-returning). Kept
    // separate from `emitted` so the call-arg typing knows to route through
    // `walkString`, not `walkLong`.
    val stringReturning: scala.collection.mutable.HashSet[String] =
      scala.collection.mutable.HashSet.empty
    // Siblings co-emitted as `static Object f(...)` (ref-returning). Kept
    // separate so a numeric call never reuses an Object-returning method.
    val refReturning: scala.collection.mutable.HashSet[String] =
      scala.collection.mutable.HashSet.empty

  // Per-param ref/int classification. A param is ref when it is the scrutinee
  // of a `Term.Match` body (top-level or nested expression form). A param read
  // only in arithmetic remains primitive long/double.
  // Shared pure classifier — see JitPredicates / specs/jit-predicates-shared-rest.md.
  private def classifyParamRefs(f: Value.FunV): Array[Boolean] =
    JitPredicates.classifyParamRefs(f)

  private def jitCompatibleSibling(f: Value.FunV): Boolean =
    f.name.nonEmpty &&
      (f.params.length >= 1 && f.params.length <= 3) &&
      f.usingParams.isEmpty &&
      !f.returnsThrows &&
      (f.defaults.isEmpty || f.defaults.forall(_.isEmpty)) &&
      (f.paramTypes.isEmpty || !f.paramTypes.exists(_.endsWith("*")))

  private def walkFunctionBody(
    f:      Value.FunV,
    ctx:    GenCtx,
    interp: scalascript.interpreter.Interpreter
  ): String | Null =
    f.body match
      case tm: Term.Match if isTupleMatch(tm) =>
        walkTupleMatchBody(tm, ctx, interp)
      case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
        walkMatchBody(tm, ctx, interp)
      case tm: Term.Match if isLiteralIntMatch(tm) =>
        walkLiteralMatchBody(tm, ctx)
      case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
        // Direction A.5: multi-stat block — emit val-bindings as Java locals,
        // then return the final expression. Special case: when the final stat
        // is a param-ref match, use walkMatchBody for it (preserves the
        // switch-statement form instead of wrapping in an IIFE).
        b.stats.last match
          case tm: Term.Match if isTupleMatch(tm) =>
            walkBlockStmts(b.stats.init, ctx) match
              case null   => null
              case prefix =>
                val tailCtx = blockStmtsCtx(b.stats.init, ctx)
                if tailCtx == null then null
                else
                  val matchPart = walkTupleMatchBody(tm, tailCtx, interp)
                  if matchPart == null then null else prefix + matchPart
          case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
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
      case Term.Try.After_4_9_9(tryExpr: Term, Some(handler), None) if handler.cases.nonEmpty =>
        walkTryCatchBody(tryExpr, handler.cases, ctx)
      case other =>
        val tco = tryTcoBody(other.asInstanceOf[Term], ctx)
        if tco != null then tco
        else if ctx.isDouble then
          val e = walkDouble(other.asInstanceOf[Term], ctx)
          if e == null then null else s"return $e;"
        else
          val e = walkLong(other.asInstanceOf[Term], ctx)
          if e != null then s"return $e;"
          else
            // Bool-body fallback: if walkLong fails but walkBool succeeds, wrap as
            // `return (boolExpr) ? 1L : 0L` so predicate functions compile.
            val b = walkBool(other.asInstanceOf[Term], ctx)
            if b == null then null else s"return ($b) ? 1L : 0L;"

  private def walkTryCatchBody(tryExpr: Term, cases: List[scala.meta.Case], ctx: GenCtx): String | Null =
    val tryStr =
      if ctx.isDouble then walkDouble(tryExpr, ctx)
      else walkLong(tryExpr, ctx)
    if tryStr == null then return null
    val sb = new StringBuilder
    sb.append(s"try { return $tryStr; }")
    var rem = cases
    while rem.nonEmpty do
      val c = rem.head; rem = rem.tail
      val (param, catchCtx) = c.pat match
        case _: Pat.Wildcard => ("Exception _exCaught", ctx)
        case pv: Pat.Var =>
          ("Exception _exCaught", ctx.withBindings(Seq(pv.name.value -> ("(Object) _exCaught", true))))
        case Pat.Typed(_: Pat.Wildcard, _) => ("Exception _exCaught", ctx)
        case Pat.Typed(pv: Pat.Var, _) =>
          ("Exception _exCaught", ctx.withBindings(Seq(pv.name.value -> ("(Object) _exCaught", true))))
        case _ => return null
      val catchStr =
        if ctx.isDouble then walkDouble(c.body.asInstanceOf[Term], catchCtx)
        else walkLong(c.body.asInstanceOf[Term], catchCtx)
      if catchStr == null then return null
      sb.append(s" catch ($param) { return $catchStr; }")
    sb.toString

  private def ensureCoEmittedLong(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.refReturning.contains(fnName) then return null
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) then return null
    val paramIsRef = classifyParamRefs(fn)
    val isDouble = bodyHasDoubleLit(fn.body)
    if isDouble then return null
    val sig = MethodSig(fn.params.toArray, paramIsRef, isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    val fnCtx = new GenCtx(fn.name, fn.params.toSet, sig.paramNames, sig.paramIsRef, isDouble = false, Map.empty, ctx.interp, ctx.coEmit)
    val bodyStmts = walkFunctionBody(fn, fnCtx, ctx.interp)
    ctx.coEmit.emitting.remove(fnName)
    if bodyStmts == null then
      ctx.coEmit.signatures.remove(fnName)
      return null
    val params = fn.params.zipWithIndex.map { case (p, i) =>
      if sig.paramIsRef(i) then s"Object ${sanitize(p)}" else s"long ${sanitize(p)}"
    }.mkString(", ")
    val methodSrc =
      s"""  public static long ${sanitize(fn.name)}($params) {
         |    $bodyStmts
         |  }
         |""".stripMargin
    ctx.coEmit.extraMethods.put(fnName, methodSrc)
    ctx.coEmit.emitted.add(fnName)
    sig

  private def emitLongCall(fnName: String, args: List[Term], ctx: GenCtx): String | Null =
    val sig =
      if fnName == ctx.funName then MethodSig(ctx.paramNames, ctx.paramIsRef, ctx.isDouble)
      else ensureCoEmittedLong(fnName, ctx)
    if sig != null && !sig.isDouble && args.length == sig.paramNames.length then
      val sb = new StringBuilder
      sb.append(sanitize(fnName)).append('(')
      var i = 0
      var rem = args
      while rem.nonEmpty do
        if i > 0 then sb.append(", ")
        val argStr =
          if sig.paramIsRef(i) then walkRef(rem.head, ctx)
          else walkLong(rem.head, ctx)
        if argStr == null then return null
        sb.append(argStr)
        i += 1
        rem = rem.tail
      return sb.append(')').toString
    // Co-emit failed. If the callee is a global FunV with all-Long params,
    // fall back to JitGlobals.callGlobalLong1/2/3 so the CALLER can still JIT.
    if fnName == ctx.funName then return null  // self-recursion must co-emit
    val n = args.length
    if n < 1 || n > 3 then return null
    ctx.interp.globals.getOrElse(fnName, null) match
      // Stage 8: callee may have `using` params in addition to regular params.
      // The interpreter's `invoke` resolves givens at runtime; the JIT just
      // passes the n regular args. Compare against (params.length - usingParams.length).
      case fn: Value.FunV if fn.params.length - fn.usingParams.length == n =>
        val paramIsRefAll = classifyParamRefs(fn)
        // 1-arg ref calls (incl. with using params) route through callGlobalLong1Ref.
        if n == 1 then
          if paramIsRefAll(0) then
            val e = walkRef(args.head, ctx)
            if e == null then return null
            val jkg = "scalascript.interpreter.vm.jit.JitGlobals$.MODULE$"
            return s"""$jkg.callGlobalLong1Ref("${escape(fnName)}", (Object) ($e))"""
        // Stage 8: 2/3-arg mixed ref/long calls (incl. with using params) route
        // through callGlobalLongAny — accepts Object[] of args, interp.invoke
        // handles given resolution and arg dispatch.
        if (n == 2 || n == 3) && (paramIsRefAll.exists(identity) || fn.usingParams.nonEmpty) then
          val argExprs = new Array[String](n)
          var i = 0
          while i < n do
            val isRef = i < paramIsRefAll.length && paramIsRefAll(i)
            val e =
              if isRef then walkRef(args(i), ctx)
              else
                val l = walkLong(args(i), ctx)
                if l == null then null else s"java.lang.Long.valueOf($l)"
            if e == null then return null
            argExprs(i) = e
            i += 1
          val jkg = "scalascript.interpreter.vm.jit.JitGlobals$.MODULE$"
          val argArr = s"new Object[] { ${argExprs.mkString(", ")} }"
          return s"""$jkg.callGlobalLongAny("${escape(fnName)}", $argArr)"""
        // Existing all-Long path requires no using params and no ref params.
        if fn.usingParams.nonEmpty then return null
        if paramIsRefAll.exists(identity) then return null  // ref params → different dispatch
        val argExprs = new Array[String](n)
        var i = 0; var rem = args
        while rem.nonEmpty do
          val e = walkLong(rem.head, ctx); if e == null then return null
          argExprs(i) = e; i += 1; rem = rem.tail
        val jkg = "scalascript.interpreter.vm.jit.JitGlobals$.MODULE$"
        if n == 1 then s"""$jkg.callGlobalLong1("${escape(fnName)}", ${argExprs(0)})"""
        else if n == 2 then s"""$jkg.callGlobalLong2("${escape(fnName)}", ${argExprs(0)}, ${argExprs(1)})"""
        else                 s"""$jkg.callGlobalLong3("${escape(fnName)}", ${argExprs(0)}, ${argExprs(1)}, ${argExprs(2)})"""
      case _ =>
        // HOF param call: `f(arg)` where `f` is a function-typed parameter.
        // Emit `((LongFnN) f).apply(args...)` so the caller can still JIT even when
        // `f` is not a static global (the FunV is wrapped as LongFnN at call time).
        val paramIdx = ctx.paramNames.indexOf(fnName)
        if paramIdx >= 0 && paramIdx < ctx.paramTypes.length && ctx.paramTypes(paramIdx).contains("=>") then
          val ref = ctx.resolveLocal(fnName)
          if ref == null then return null
          if n == 1 then
            val a = walkLong(args.head, ctx); if a == null then return null
            s"((scalascript.interpreter.vm.jit.LongFn1) $ref).apply($a)"
          else if n == 2 then
            val a1 = walkLong(args.head, ctx); if a1 == null then return null
            val a2 = walkLong(args(1), ctx);   if a2 == null then return null
            s"((scalascript.interpreter.vm.jit.LongFn2) $ref).apply($a1, $a2)"
          else if n == 3 then
            val a1 = walkLong(args.head, ctx); if a1 == null then return null
            val a2 = walkLong(args(1), ctx);   if a2 == null then return null
            val a3 = walkLong(args(2), ctx);   if a3 == null then return null
            s"((scalascript.interpreter.vm.jit.LongFn3) $ref).apply($a1, $a2, $a3)"
          else null
        else null

  private def ensureCoEmittedRef(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.refReturning.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return null
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) || bodyHasDoubleLit(fn.body) then return null
    val sig = MethodSig(fn.params.toArray, classifyParamRefs(fn), isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    val fnCtx = new GenCtx(fn.name, fn.params.toSet, sig.paramNames, sig.paramIsRef,
      isDouble = false, Map.empty, ctx.interp, ctx.coEmit, fn.paramTypes.toArray)
    var extraStatics = ""
    val methodBody =
      if fn.params.length == 1 && sig.paramIsRef(0) then
        fn.body match
          case tm: Term.Match => walkRefMatchBody(tm, fnCtx, ctx.interp)
          case _              => null
      else
        val statics = scala.collection.mutable.LinkedHashMap.empty[String, String]
        val expr = walkObject(fn.body.asInstanceOf[Term], fnCtx, statics)
        if expr == null then null
        else
          extraStatics = statics.valuesIterator.mkString
          s"return $expr;"
    ctx.coEmit.emitting.remove(fnName)
    if methodBody == null then
      ctx.coEmit.signatures.remove(fnName)
      return null
    val methodSrc =
      s"""$extraStatics  public static Object ${sanitize(fn.name)}(${emitParamDecls(fn, sig)}) {
         |    $methodBody
         |  }
         |""".stripMargin
    ctx.coEmit.extraMethods.put(fnName, methodSrc)
    ctx.coEmit.emitted.add(fnName)
    ctx.coEmit.refReturning.add(fnName)
    sig

  private def emitParamDecls(fn: Value.FunV, sig: MethodSig): String =
    fn.params.zipWithIndex.map { case (p, i) =>
      if sig.paramIsRef(i) then s"Object ${sanitize(p)}" else s"long ${sanitize(p)}"
    }.mkString(", ")

  private def emitRefCall(fnName: String, args: List[Term], ctx: GenCtx): String | Null =
    val sig =
      if fnName == ctx.funName then null
      else ensureCoEmittedRef(fnName, ctx)
    if sig == null || args.length != sig.paramNames.length then return null
    val sb = new StringBuilder
    sb.append(sanitize(fnName)).append('(')
    var i = 0
    var rem = args
    while rem.nonEmpty do
      if i > 0 then sb.append(", ")
      val argStr = if sig.paramIsRef(i) then walkRef(rem.head, ctx) else walkLong(rem.head, ctx)
      if argStr == null then return null
      sb.append(argStr)
      i += 1
      rem = rem.tail
    sb.append(')').toString

  /** slice 2: if `t` is a builtin indexable-seq constructor (`Array`/`Vector`/`List`), return
   *  Some(isArray) — true iff it's a mutable `Array` (so `a(i)=x` stores are allowed); else None.
   *  Lets a `val a = Array(…)` local be tracked as a seq so subsequent `a(idx)` reads and
   *  `a(idx)=x` stores JIT (a local can't be classified by runtime type like a global). */
  private def seqCtorKind(t: Term): Option[Boolean] = t match
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => seqCtorKind(inner)
        case _           => None
    case Term.Apply.After_4_6_0(funExpr, _) =>
      val name = funExpr match
        case Term.Name(n) => n
        case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
        case _ => ""
      if name == "Array" then Some(true)
      else if name == "Vector" || name == "List" then Some(false)
      else None
    case _ => None

  private def isRefValRhs(t: Term, ctx: GenCtx): Boolean = t match
    case Term.Name("None") => true
    case tn: Term.Name     => ctx.isRefName(tn.value)
    case _: Term.Tuple     => true  // T3: tuple literal → TupleV (ref), compiled by walkRef.
    case ai: Term.ApplyInfix if ai.op.value == "++" => true  // T3: ++ concat (List/Map/Tuple) → ref.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => isRefValRhs(inner, ctx)
        case _           => false
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" =>
          ap.argClause.values.lengthCompare(1) == 0
        case fn: Term.Name if fn.value == "List" || fn.value == "Set" || fn.value == "Map"
            || fn.value == "Array" || fn.value == "Vector" =>
          // Stage 8 / slice 2: builtin collection ctors compile via walkRef.
          true
        case Term.ApplyType.After_4_6_0(Term.Name(n), _)
            if n == "List" || n == "Set" || n == "Map" || n == "Array" || n == "Vector" =>
          true
        case Term.Select(Term.Name(recvName), Term.Name("updated"))
            if ctx.isRefName(recvName) =>
          true  // Stage 8: Map.updated on a ref var returns Map (ref).
        case fn: Term.Name if !builtinAdtCtorNames.contains(fn.value) && ctx.interp.typeFieldOrder.contains(fn.value) =>
          true  // T3: case-class / ADT constructor → InstanceV (ref), compiled by walkRef.
        case fn: Term.Name =>
          ensureCoEmittedRef(fn.value, ctx) != null
        case _ => false
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      val tn = ctx.refTypeOf(objName)
      if tn == null then false
      else
        val fo = ctx.interp.typeFieldOrder.getOrElse(tn, Nil)
        val idx = fo.indexOf(field)
        if idx < 0 then false
        else
          val ft = ctx.interp.typeFieldTypes.getOrElse(tn, Nil)
          val ftype = if idx < ft.length then ft(idx) else ""
          ftype != "Int" && ftype != "Long" && ftype != "Double"
    case _ => false

  /** Emit a Java `String`-typed expression. Supports string literals, `+`
   *  concatenation (with a numeric operand auto-coerced via `walkLong`),
   *  `String`-typed param reads, and calls to String-returning siblings.
   *  Returns null (caller bails) for any other shape. */
  private def walkString(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.String(v) => "\"" + escape(v) + "\""
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkString(inner, ctx)
        case _           => null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if op.value == "+" && argClause.values.lengthCompare(1) == 0 =>
      val rhsT = argClause.values.head
      val lS = walkString(lhs, ctx)
      val rS = walkString(rhsT, ctx)
      // Java `+` is String concat only if at least one operand is a String.
      if lS == null && rS == null then return null
      val lStr = if lS != null then lS else walkLong(lhs, ctx)
      val rStr = if rS != null then rS else walkLong(rhsT, ctx)
      if lStr == null || rStr == null then return null
      s"($lStr + $rStr)"
    case tn: Term.Name =>
      if ctx.isStringName(tn.value) then ctx.resolveLocal(tn.value) else null
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name => emitStringCall(fn.value, ap.argClause.values, ctx)
        case _             => null
    case _ => null

  /** Co-emit a String-returning sibling `f` as a `static String f(...)` helper.
   *  Mirrors `ensureCoEmittedLong` but walks the body via `walkString`. Returns
   *  the sibling's `MethodSig` on success (params still long/ref), or null. */
  private def ensureCoEmittedString(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.stringReturning.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return null
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) then return null
    val paramIsRef = classifyParamRefs(fn)
    if bodyHasDoubleLit(fn.body) then return null
    val sig = MethodSig(fn.params.toArray, paramIsRef, isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    val fnCtx = new GenCtx(fn.name, fn.params.toSet, sig.paramNames, sig.paramIsRef,
      isDouble = false, Map.empty, ctx.interp, ctx.coEmit, fn.paramTypes.toArray)
    val bodyStr = walkString(fn.body.asInstanceOf[Term], fnCtx)
    ctx.coEmit.emitting.remove(fnName)
    if bodyStr == null then
      ctx.coEmit.signatures.remove(fnName)
      return null
    val params = fn.params.zipWithIndex.map { case (p, i) =>
      if sig.paramIsRef(i) then s"Object ${sanitize(p)}" else s"long ${sanitize(p)}"
    }.mkString(", ")
    val methodSrc =
      s"""  public static String ${sanitize(fn.name)}($params) {
         |    return $bodyStr;
         |  }
         |""".stripMargin
    ctx.coEmit.extraMethods.put(fnName, methodSrc)
    ctx.coEmit.emitted.add(fnName)
    ctx.coEmit.stringReturning.add(fnName)
    sig

  private def emitStringCall(fnName: String, args: List[Term], ctx: GenCtx): String | Null =
    val sig = ensureCoEmittedString(fnName, ctx)
    if sig == null || args.length != sig.paramNames.length then return null
    val sb = new StringBuilder
    sb.append(sanitize(fnName)).append('(')
    var i = 0
    var rem = args
    while rem.nonEmpty do
      if i > 0 then sb.append(", ")
      val argStr =
        if sig.paramIsRef(i) then walkRef(rem.head, ctx)
        else walkLong(rem.head, ctx)
      if argStr == null then return null
      sb.append(argStr)
      i += 1
      rem = rem.tail
    sb.append(')').toString

  private def callParamIsRef(fnName: String, argIdx: Int, ctx: GenCtx): Boolean =
    val sig =
      if fnName == ctx.funName then MethodSig(ctx.paramNames, ctx.paramIsRef, ctx.isDouble)
      else
        ctx.coEmit.signatures.get(fnName) match
          case Some(s) => s
          case None =>
            ctx.interp.globals.getOrElse(fnName, null) match
              case fn: Value.FunV if jitCompatibleSibling(fn) =>
                MethodSig(fn.params.toArray, classifyParamRefs(fn), bodyHasDoubleLit(fn.body))
              case _ => null
    sig != null && argIdx >= 0 && argIdx < sig.paramIsRef.length && sig.paramIsRef(argIdx)

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
    val interp:      scalascript.interpreter.Interpreter,
    val coEmit:      CoEmitState,
    val paramTypes:  Array[String] = Array.empty,
    // Stage 9: val-bound lambdas — name → (paramNames, body). Used to inline
    // a lambda body at the call site when the lambda escapes only via direct
    // call (no HOF arg, no return). Avoids needing a runtime FunV constant.
    val lambdas:     Map[String, (Array[String], Term)] = Map.empty,
    // jit-collection-ops slice 2: ref-local names statically known to hold an
    // indexable seq (bound to `Array(...)`/`Vector(...)`/`List(...)`). Value is
    // true iff the seq is a mutable `Array` (so `a(i)=x` stores are allowed).
    // Lets `a(idx)` read JIT as seqIndexLong on a *local* (slice 1 only did globals).
    val seqLocals:   Map[String, Boolean] = Map.empty,
    // Nested-match uniquifier: how deeply the current expression is nested inside
    // enclosing `match` scopes IN THE SAME JAVA METHOD. A nested match compiles to
    // an IIFE lambda whose body re-declares the same helper locals (`inst`,
    // `__fa_<ctor>`, `<bind>_a`, …); Java forbids a lambda-body local from
    // shadowing an enclosing-method local, so those names must differ per level.
    // `nameSuffix` is appended to every such local; it is "" at depth 0, so the
    // common (non-nested) case is byte-identical to before. Depth strictly
    // increases from an enclosing match to a nested one, so no two colliding
    // (ancestor/descendant) scopes ever share a suffix. (bug:
    // interp-jit-nested-match-duplicate-var.)
    val matchDepth:  Int = 0
  ) extends JitShapeCtx:
    /** Per-match-scope suffix for emitted helper locals; "" at the outermost
     *  (depth 0) match so non-nested codegen is unchanged. */
    def nameSuffix: String = if matchDepth == 0 then "" else "_" + matchDepth
    // JitShapeCtx: a local Long is one that resolves to a Java variable.
    def isLocalLong(n: String): Boolean = resolveLocal(n) != null
    def isLambda(n: String): Boolean    = lambdas.contains(n)
    def globalIsIntV(n: String): Boolean =
      interp.globals.get(n).exists(_.isInstanceOf[Value.IntV])
    def globalIsFunV(n: String): Boolean =
      interp.globals.get(n).exists(_.isInstanceOf[Value.FunV])
    override def isSeqIndexName(n: String): Boolean =
      seqLocals.contains(n) || (!isRefName(n) && (interp.globals.get(n) match
        case Some(_: Value.VectorV) | Some(_: Value.ListV) | Some(_: Value.ArrayV) => true
        case _ => false))
    def callArgIsRef(fnName: String, argIdx: Int): Boolean =
      callParamIsRef(fnName, argIdx, this)
    def isRefName(n: String): Boolean =
      bindings.get(n) match
        case Some((_, r)) => r
        case None =>
          val idx = paramNames.indexOf(n)
          idx >= 0 && paramIsRef(idx)
    /** True iff `n` is a `String`-typed param (used by `walkString`). Pattern
     *  bindings are never String-typed in the current lane. */
    def isStringName(n: String): Boolean =
      !bindings.contains(n) && {
        val idx = paramNames.indexOf(n)
        idx >= 0 && idx < paramTypes.length && paramTypes(idx) == "String"
      }
    def resolveLocal(n: String): String | Null =
      bindings.get(n) match
        case Some((jvar, _)) => jvar
        case None =>
          if params.contains(n) then sanitize(n) else null
    /** Declared type name of a ref param (e.g. "Vec"), or null if unknown. */
    def refTypeOf(n: String): String | Null =
      val idx = paramNames.indexOf(n)
      if idx >= 0 && idx < paramTypes.length then paramTypes(idx) else null
    def withBindings(more: Iterable[(String, (String, Boolean))]): GenCtx =
      new GenCtx(funName, params, paramNames, paramIsRef, isDouble, bindings ++ more, interp, coEmit, paramTypes, lambdas, seqLocals, matchDepth)
    def withLambda(name: String, paramNamesL: Array[String], body: Term): GenCtx =
      new GenCtx(funName, params, paramNames, paramIsRef, isDouble, bindings, interp, coEmit, paramTypes, lambdas + (name -> (paramNamesL, body)), seqLocals, matchDepth)
    /** Bind a ref-local AND record it as a seq (slice 2). `isArray` enables `a(i)=x` stores. */
    def withSeqLocal(name: String, jvar: String, isArray: Boolean): GenCtx =
      new GenCtx(funName, params, paramNames, paramIsRef, isDouble, bindings + (name -> (jvar, true)), interp, coEmit, paramTypes, lambdas, seqLocals + (name -> isArray), matchDepth)
    /** Enter a nested `match` scope: bumps the uniquifier depth so the nested
     *  match's helper locals (`inst`, `__fa_<ctor>`, …) don't collide with the
     *  enclosing match's in the same Java method. */
    def deeperMatch: GenCtx =
      new GenCtx(funName, params, paramNames, paramIsRef, isDouble, bindings, interp, coEmit, paramTypes, lambdas, seqLocals, matchDepth + 1)

  /** Stage 9: inline a val-bound lambda's body at the call site by binding
   *  each lambda param to the arg's *Java expression* directly (no local var,
   *  no IIFE — avoids Java's effectively-final capture rule). The arg
   *  expression is wrapped in parens to preserve precedence. Returns null on
   *  any walk failure. */
  private def inlineLambdaLong(name: String, args: List[Term], ctx: GenCtx): String | Null =
    val (pNames, body) = ctx.lambdas(name)
    if args.length != pNames.length then return null
    var newCtx = ctx
    var i      = 0
    var rem    = args
    while rem.nonEmpty do
      val pn  = pNames(i)
      val arg = walkLong(rem.head, newCtx)
      if arg == null then return null
      // Bind param to the parenthesised Java expression directly. Safe because
      // walkLong's "Term.Name" path returns the bound string verbatim.
      newCtx = newCtx.withBindings(Seq(pn -> (s"($arg)", false)))
      i += 1; rem = rem.tail
    walkLong(body, newCtx)

  private def walkLong(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Int(v)     => s"${v}L"
    case Lit.Long(v)    => s"${v}L"
    case Lit.Boolean(b) => if b then "1L" else "0L"
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
    // `deeperMatch` bumps the nested-match uniquifier so this expression-position
    // match's helper locals don't collide with an enclosing match's in the same
    // Java method (bug: interp-jit-nested-match-duplicate-var).
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx.deeperMatch, ctx.interp)
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
    // effect-vm-continuations (spec Phase 2): a 0-arg effect-op call `Eff.op()` whose
    // `(eff,op)` has a live one-shot tail-resume resolver AT COMPILE TIME (the JIT runs during
    // the handle's body eval, when evalHandle has installed the resolver) lowers to a bridge
    // call. The bridge throws if the resolver is absent at run time; tryWhileJit's run is
    // try/catch-wrapped and writes its slots back only on success, so the loop bails cleanly.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name(eff), Term.Name(op)), ac)
        if ac.values.lengthCompare(2) <= 0
        && scalascript.interpreter.EffectsRuntime.lookupResolver(eff, op) != null =>
      val jkg = "scalascript.interpreter.vm.jit.JitGlobals$.MODULE$"
      ac.values match
        case Nil => s"""$jkg.resolveEffectLong("${escape(eff)}", "${escape(op)}")"""
        case args =>
          // P2b: arg-carrying op — args must be numeric (walkLong each; bail otherwise).
          val argExprs = args.map(a => walkLong(a, ctx))
          if argExprs.exists(_ == null) then null
          else
            val fn = if args.lengthCompare(1) == 0 then "resolveEffectLong1" else "resolveEffectLong2"
            s"""$jkg.$fn("${escape(eff)}", "${escape(op)}", ${argExprs.mkString(", ")})"""
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      val opStr = op.value
      opStr match
        // Stage 8: BigInt/Decimal comparison ops — route to JitRefDispatch helpers.
        case "<" | "<=" | ">" | ">=" if isNumericObjectReceiver(lhs) =>
          val helper = (lhs, opStr) match
            case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "<")  => "bigIntLt"
            case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "<=") => "bigIntLe"
            case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), ">")  => "bigIntGt"
            case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), ">=") => "bigIntGe"
            case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "<")  => "decimalLt"
            case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "<=") => "decimalLe"
            case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), ">")  => "decimalGt"
            case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), ">=") => "decimalGe"
            case _ => null
          if helper == null then null
          else
            val recv = emitNumericObjectValue(lhs, ctx)
            val other = emitValueObject(argClause.values.head, ctx)
            if recv == null || other == null then null
            else s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.$helper((Object) ($recv), $other)"
        case "+" | "-" | "*" | "/" | "%" =>
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"(($l $opStr $r) ? 1L : 0L)"
        case _ => null
    // T3: numeric tuple element access `t._1` … `t._n` on a ref-typed tuple local.
    // Must precede the generic ADT-field case (a tuple has no field meta).
    case Term.Select(Term.Name(objName), Term.Name(field))
        if ctx.isRefName(objName) && field.length > 1 && field.charAt(0) == '_'
           && field.substring(1).forall(_.isDigit) =>
      val jn = ctx.resolveLocal(objName)
      if jn == null then null
      else s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.tupleIntElem((Object)($jn), ${field.substring(1).toInt})"
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      emitRefFieldNumeric(objName, field, ctx, wantDouble = false)
    case Term.Select(recv: Term, Term.Name("size")) =>
      emitRefChainLong(recv, "size", Nil, ctx)
    case Term.Select(recv: Term, Term.Name("head")) =>
      emitRefChainLong(recv, "head", Nil, ctx)
    // Bare zero-arg accessors the ref-dispatch helpers return as Long: the Bool
    // accessors (0L/1L) and the `.last` element accessor (mirrors `.head`). Like
    // `.size`, these resolve a global/collection receiver via walkRef. Without a
    // bare-Select route they fell to `case _ => null` and bailed the whole loop
    // (e.g. `while … if csv.nonEmpty …`, `xs.isEmpty`, `xs.last.toLong`). A `.last`
    // on a ref-element list throws at runtime; the JIT invocation guard then falls
    // back to the tree-walk, so the common numeric case is fast and the rest stays
    // correct. `looksLongValue` lists the same set so `.toLong` does not mis-route.
    case Term.Select(recv: Term, Term.Name(m @ ("isEmpty" | "nonEmpty" | "isDefined" | "last"))) =>
      emitRefChainLong(recv, m, Nil, ctx)
    // `<stringExpr>.length` → Java `(long)(<str>).length()`. Lets a numeric body
    // consume the length of a JIT-compiled String expression (e.g. `label(i).length`).
    // When the receiver is not a directly-walkable String literal/local (e.g. a
    // GLOBAL `val csv: String` or a collection), `walkString` returns null —
    // fall back to the ref-dispatch `sizeLong`, which resolves the receiver via
    // `walkRef` (globals included) and measures String/List/Map/Set/Tuple alike.
    // Without this fallback a bare `.length` bailed the WHOLE enclosing loop to a
    // tree-walk (the common `while … csv.length …` pattern), ~280× slower.
    case Term.Select(recv, Term.Name("length")) =>
      val s = walkString(recv, ctx)
      if s != null then s"((long)($s).length())"
      else emitRefChainLong(recv, "length", Nil, ctx)
    // jit-collection-ops slice 2: fused `LazyList.from(s).map(f)?.take(n).sum` → native loop,
    // no lazy cons/thunk allocation. Guarded on the exact pipeline shape so other `.sum` bail.
    case Term.Select(recv: Term, Term.Name("sum")) if JitHofShape.lazyFromMapTake(recv) != null =>
      val pipe  = JitHofShape.lazyFromMapTake(recv)
      val start = walkLong(pipe.start, ctx)
      val n     = walkLong(pipe.n, ctx)
      if start == null || n == null then null
      else
        val hasMap = pipe.map != null
        val op     = if hasMap then pipe.map.op else 0
        val c      = if hasMap then pipe.map.c else 0L
        s"scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.lazyFromMapTakeSum($start, $hasMap, $op, ${c}L, $n)"
    // `.toLong` / `.toInt` are no-ops in ScalaScript when inner is Long-typed.
    // Stage 8: when inner walks as ref (e.g. String), route to stringToIntLong.
    case Term.Select(inner: Term, Term.Name("toLong" | "toInt")) =>
      val asLong = walkLong(inner, ctx)
      if asLong != null then asLong
      else emitRefChainLong(inner, "toInt", Nil, ctx)
    // `.toDouble` on a Long expression — widen.
    case Term.Select(inner: Term, Term.Name("toDouble")) =>
      val e = walkLong(inner, ctx)
      if e == null then null else s"(double)($e)"
    // Stage 8: `.abs` on a Long expression → java.lang.Math.abs(...).
    case Term.Select(inner: Term, Term.Name("abs")) =>
      val e = walkLong(inner, ctx)
      if e == null then null else s"java.lang.Math.abs($e)"
    case ap: Term.Apply =>
      ap.fun match
        case inner: Term.Apply =>
          emitHofFoldLeftLong(inner, ap.argClause.values, ctx)
        // Stage 8: top-level Math.max(a, b) / Math.min(a, b) / Math.abs(a).
        case Term.Select(Term.Name("Math"), Term.Name(method))
            if (method == "max" || method == "min")
              && ap.argClause.values.lengthCompare(2) == 0 =>
          val a = walkLong(ap.argClause.values.head, ctx)
          val b = walkLong(ap.argClause.values(1), ctx)
          if a == null || b == null then null
          else s"java.lang.Math.$method($a, $b)"
        case Term.Select(Term.Name("Math"), Term.Name("abs"))
            if ap.argClause.values.lengthCompare(1) == 0 =>
          val a = walkLong(ap.argClause.values.head, ctx)
          if a == null then null else s"java.lang.Math.abs($a)"
        // Stage 8: `a.max(b)` / `a.min(b)` on Long expressions.
        case Term.Select(recv: Term, Term.Name(method))
            if (method == "max" || method == "min")
              && ap.argClause.values.lengthCompare(1) == 0 =>
          val l = walkLong(recv, ctx)
          if l == null then
            val hof = emitHofFoldLong(recv, method, ap.argClause.values, ctx)
            if hof != null then hof else emitRefChainLong(recv, method, ap.argClause.values, ctx)
          else
            val r = walkLong(ap.argClause.values.head, ctx)
            if r == null then null else s"java.lang.Math.$method($l, $r)"
        case Term.Select(recv: Term, Term.Name(method)) =>
          val hof = emitHofFoldLong(recv, method, ap.argClause.values, ctx)
          if hof != null then hof else emitRefChainLong(recv, method, ap.argClause.values, ctx)
        // Stage 9: inline a val-bound lambda call site (no FunV runtime alloc).
        case fn: Term.Name if ctx.lambdas.contains(fn.value) =>
          inlineLambdaLong(fn.value, ap.argClause.values, ctx)
        // jit-collection-ops: `seq(idx)` indexed read on a seq-typed global (Vector/List/Array) →
        // O(1) JitRefDispatch.seqIndexLong. Discriminated from a function call by the global's runtime
        // type (the loop is compiled only after it is hot, so the seq is already constructed).
        case fn: Term.Name if ap.argClause.values.lengthCompare(1) == 0
            && (isSeqRefName(fn.value, ctx) || ctx.seqLocals.contains(fn.value)) =>
          val ref = walkRef(fn, ctx)
          val idx = walkLong(ap.argClause.values.head, ctx)
          if ref == null || idx == null then null
          else s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.seqIndexLong((Object) ($ref), $idx)"
        case fn: Term.Name => emitLongCall(fn.value, ap.argClause.values, ctx)
        case _ => null
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkLong(arg, ctx); if a == null then return null
      s"(${op.value}$a)"
    case _ => null

  /** Is `n` a name that currently refers to an indexable sequence GLOBAL (`Vector`/`List`/`Array`)?
   *  Used to discriminate `seq(idx)` indexing from a function call. Restricted to globals (whose
   *  runtime type is known at JIT-compile time); ref-locals are deferred to slice 2. A `FunV` global
   *  (a function) correctly fails this test. (jit-collection-ops.) */
  private def isSeqRefName(n: String, ctx: GenCtx): Boolean =
    !ctx.isRefName(n) && (ctx.interp.globals.get(n) match
      case Some(_: Value.VectorV) | Some(_: Value.ListV) | Some(_: Value.ArrayV) => true
      case _ => false)

  private def emitRefChainLong(recv: Term, method: String, args: List[Term], ctx: GenCtx): String | Null =
    val refExpr = walkRef(recv, ctx)
    if refExpr == null then return null
    val jrd = "scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$"
    method match
      case "getOrElse" if args.lengthCompare(1) == 0 =>
        val d = walkLong(args.head, ctx)
        if d == null then null
        else
          // Fuse a preceding `.map(unary)` into the getOrElse sink (no wrapper alloc).
          peelMapUnary(recv) match
            case (inner, op) =>
              val innerRef = walkRef(inner, ctx)
              if innerRef == null then s"$jrd.getOrElseLong((Object) ($refExpr), $d)"
              else s"scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.mapGetOrElseLong((Object) ($innerRef), ${op.op}, ${op.c}L, $d)"
            case null =>
              s"$jrd.getOrElseLong((Object) ($refExpr), $d)"
      // Stage 8: Map.getOrElse(key, default) → Long.
      case "getOrElse" if args.lengthCompare(2) == 0 =>
        val k = emitValueObject(args.head, ctx)
        val d = walkLong(args(1), ctx)
        if k == null || d == null then null
        else s"$jrd.mapGetOrElseLong((Object) ($refExpr), $k, $d)"
      // `.size` and `.length` are interchangeable on String/List/Map/Set/Tuple;
      // sizeLong handles every receiver, so route both through it.
      case "size" | "length" if args.isEmpty =>
        s"$jrd.sizeLong((Object) ($refExpr))"
      case "head" if args.isEmpty =>
        s"$jrd.headLong((Object) ($refExpr))"
      // Stage 8: more List-on-Long methods.
      case "last" if args.isEmpty =>
        s"$jrd.lastLong((Object) ($refExpr))"
      case "isEmpty" if args.isEmpty =>
        s"$jrd.isEmptyLong((Object) ($refExpr))"
      case "nonEmpty" if args.isEmpty =>
        s"$jrd.nonEmptyLong((Object) ($refExpr))"
      // Stage 8: Option / collection-contains methods returning Long.
      case "isDefined" if args.isEmpty =>
        s"$jrd.isDefinedLong((Object) ($refExpr))"
      case "get" if args.isEmpty =>
        s"$jrd.optionGetLong((Object) ($refExpr))"
      case "contains" if args.lengthCompare(1) == 0 =>
        val k = emitValueObject(args.head, ctx)
        if k == null then null else s"$jrd.containsLong((Object) ($refExpr), $k)"
      // Stage 8: String → Long methods.
      case "toInt" if args.isEmpty =>
        s"$jrd.stringToIntLong((Object) ($refExpr))"
      case "toLong" if args.isEmpty =>
        s"$jrd.stringToLongLong((Object) ($refExpr))"
      case "indexOf" if args.lengthCompare(1) == 0 =>
        val r = walkRef(args.head, ctx)
        if r == null then null else s"$jrd.stringIndexOfLong((Object) ($refExpr), (Object) ($r))"
      case "startsWith" if args.lengthCompare(1) == 0 =>
        val r = walkRef(args.head, ctx)
        if r == null then null else s"$jrd.stringStartsWithLong((Object) ($refExpr), (Object) ($r))"
      case "endsWith" if args.lengthCompare(1) == 0 =>
        val r = walkRef(args.head, ctx)
        if r == null then null else s"$jrd.stringEndsWithLong((Object) ($refExpr), (Object) ($r))"
      // Stage 8: String.charAt(i) → Long (char as Long).
      case "charAt" if args.lengthCompare(1) == 0 =>
        val i = walkLong(args.head, ctx)
        if i == null then null else s"$jrd.stringCharAtLong((Object) ($refExpr), $i)"
      case _ => null

  private def emitRefChainObject(recv: Term, method: String, args: List[Term], ctx: GenCtx): String | Null =
    if isNumericObjectReceiver(recv) then
      val numeric = emitNumericObjectMethod(recv, method, args, ctx)
      if numeric != null then return numeric
    val refExpr = walkRef(recv, ctx)
    if refExpr == null then return null
    val jrd = "scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$"
    method match
      case "getOrElse" if args.lengthCompare(1) == 0 =>
        val d = emitValueObject(args.head, ctx)
        if d == null then null else s"$jrd.getOrElseRef((Object) ($refExpr), $d)"
      case "getOrElse" if args.lengthCompare(2) == 0 =>
        val key = emitValueObject(args.head, ctx)
        val d   = emitValueObject(args(1), ctx)
        if key == null || d == null then null
        else s"$jrd.mapGetOrElseRef((Object) ($refExpr), $key, $d)"
      case "mkString" if args.isEmpty =>
        s"$jrd.mkStringRef((Object) ($refExpr))"
      case "mkString" if args.lengthCompare(1) == 0 =>
        val sep = walkString(args.head, ctx)
        if sep == null then null else s"$jrd.mkStringRef((Object) ($refExpr), $sep)"
      case "mkString" if args.lengthCompare(3) == 0 =>
        val start = walkString(args.head, ctx)
        val sep   = walkString(args(1), ctx)
        val end   = walkString(args(2), ctx)
        if start == null || sep == null || end == null then null
        else s"$jrd.mkStringRef((Object) ($refExpr), $start, $sep, $end)"
      // Stage 8: more List-returning methods.
      case "tail" if args.isEmpty =>
        s"$jrd.tailRef((Object) ($refExpr))"
      case "init" if args.isEmpty =>
        s"$jrd.initRef((Object) ($refExpr))"
      case "headOption" if args.isEmpty =>
        s"$jrd.headOptionRef((Object) ($refExpr))"
      // Stage 8: Map.keys / Map.values / Option.get (ref-typed).
      case "keys" if args.isEmpty =>
        s"$jrd.mapKeysRef((Object) ($refExpr))"
      case "values" if args.isEmpty =>
        s"$jrd.mapValuesRef((Object) ($refExpr))"
      case "get" if args.isEmpty =>
        s"$jrd.optionGetRef((Object) ($refExpr))"
      // Stage 8: String → String methods.
      case "trim" if args.isEmpty =>
        s"$jrd.stringTrimRef((Object) ($refExpr))"
      case "toUpperCase" if args.isEmpty =>
        s"$jrd.stringUpperRef((Object) ($refExpr))"
      case "toLowerCase" if args.isEmpty =>
        s"$jrd.stringLowerRef((Object) ($refExpr))"
      // Stage 8: .toString on any ref Value via Value.show.
      case "toString" if args.isEmpty =>
        s"$jrd.toStringRef((Object) ($refExpr))"
      // Stage 8: Map.updated(k, v) → new Map.
      case "updated" if args.lengthCompare(2) == 0 =>
        val k = emitValueObject(args.head, ctx)
        val v = emitValueObject(args(1), ctx)
        if k == null || v == null then null
        else s"$jrd.mapUpdatedRef((Object) ($refExpr), $k, $v)"
      // Stage 8: Map.get(key) → Option; String.substring(i)/(i,j); String.replace(o, n).
      case "get" if args.lengthCompare(1) == 0 =>
        val k = emitValueObject(args.head, ctx)
        if k == null then null else s"$jrd.mapGetRef((Object) ($refExpr), $k)"
      case "substring" if args.lengthCompare(1) == 0 =>
        val i = walkLong(args.head, ctx)
        if i == null then null else s"$jrd.stringSubstringRef((Object) ($refExpr), $i)"
      case "substring" if args.lengthCompare(2) == 0 =>
        val i = walkLong(args.head, ctx)
        val j = walkLong(args(1), ctx)
        if i == null || j == null then null
        else s"$jrd.stringSubstring2Ref((Object) ($refExpr), $i, $j)"
      case "replace" if args.lengthCompare(2) == 0 =>
        val o = walkRef(args.head, ctx)
        val n = walkRef(args(1), ctx)
        if o == null || n == null then null
        else s"$jrd.stringReplaceRef((Object) ($refExpr), (Object) ($o), (Object) ($n))"
      // Stage 8: String.split(sep) → ListV[StringV].
      case "split" if args.lengthCompare(1) == 0 =>
        val sep = walkRef(args.head, ctx)
        if sep == null then null else s"$jrd.stringSplitRef((Object) ($refExpr), (Object) ($sep))"
      case _ => null

  // Shared pure classifiers — see JitPredicates / specs/jit-predicates-shared-rest.md.
  private def isNumericObjectReceiver(recv: Term): Boolean =
    JitPredicates.isNumericObjectReceiver(recv)
  private def isNumericObjectValueShape(t: Term): Boolean =
    JitPredicates.isNumericObjectValueShape(t)

  private def emitNumericObjectValue(t: Term, ctx: GenCtx): String | Null =
    val jrd = "scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$"
    t match
      case Term.Apply.After_4_6_0(Term.Name("BigInt"), argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val v = emitValueObject(argClause.values.head, ctx)
        if v == null then null else s"$jrd.bigIntRef($v)"
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val v = emitValueObject(argClause.values.head, ctx)
        if v == null then null else s"$jrd.decimalRef($v)"
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause)
          if argClause.values.lengthCompare(2) == 0 =>
        val value = emitValueObject(argClause.values.head, ctx)
        val scale = walkLong(argClause.values(1), ctx)
        if value == null || scale == null then null else s"$jrd.decimalRef($value, $scale)"
      case _ => null

  private def emitNumericObjectMethod(recv: Term, method: String, args: List[Term], ctx: GenCtx): String | Null =
    val recvValue = emitNumericObjectValue(recv, ctx)
    if recvValue == null then return null
    val jrd = "scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$"
    recv match
      case Term.Apply.After_4_6_0(Term.Name("BigInt"), _) =>
        method match
          case "abs" if args.isEmpty =>
            s"$jrd.bigIntAbs((Object) ($recvValue))"
          case "negate" if args.isEmpty =>
            s"$jrd.bigIntNegate((Object) ($recvValue))"
          case "pow" if args.lengthCompare(1) == 0 =>
            val e = walkLong(args.head, ctx)
            if e == null then null else s"$jrd.bigIntPow((Object) ($recvValue), $e)"
          case "gcd" if args.lengthCompare(1) == 0 =>
            val other = emitValueObject(args.head, ctx)
            if other == null then null else s"$jrd.bigIntGcd((Object) ($recvValue), $other)"
          case "toDecimal" if args.isEmpty =>
            s"$jrd.bigIntToDecimal((Object) ($recvValue))"
          case _ => null
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), _) =>
        method match
          case "abs" if args.isEmpty =>
            s"$jrd.decimalAbs((Object) ($recvValue))"
          case "negate" if args.isEmpty =>
            s"$jrd.decimalNegate((Object) ($recvValue))"
          case "pow" if args.lengthCompare(1) == 0 =>
            val e = walkLong(args.head, ctx)
            if e == null then null else s"$jrd.decimalPow((Object) ($recvValue), $e)"
          case "setScale" if args.lengthCompare(1) == 0 =>
            val scale = walkLong(args.head, ctx)
            if scale == null then null else s"$jrd.decimalSetScale((Object) ($recvValue), $scale)"
          case "toBigInt" if args.isEmpty =>
            s"$jrd.decimalToBigInt((Object) ($recvValue))"
          case _ => null
      case _ => null

  // Shared with AsmJitBackend via JitPredicates so both backends classify
  // identically (see specs/jit-predicates-shared.md and specs/asm-jit-parity.md).
  // (Javac calls only objectRefFallbackAllowed directly; looksLongValue is
  // reached through it. ASM additionally calls looksLongValue at its own sites.)
  private def objectRefFallbackAllowed(t: Term, ctx: GenCtx): Boolean =
    JitPredicates.objectRefFallbackAllowed(t, ctx)

  /** If `t` is `inner.map(unary)` with a recognised arithmetic lambda, return the
   *  inner receiver and the map op so the caller can fuse the map into the next
   *  stage (flatMap / getOrElse) and skip the intermediate wrapper allocation. */
  private def peelMapUnary(t: Term): (Term, JitHofShape.UnaryLong) | Null =
    JitPredicates.peelMapUnary(t)

  private def emitHofRefChain(recv: Term, method: String, args: List[Term], ctx: GenCtx): String | Null =
    val refExpr = walkRef(recv, ctx)
    if refExpr == null then return null
    val jhd = "scalascript.interpreter.vm.jit.JitHofDispatch$.MODULE$"
    method match
      case "map" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val op = JitHofShape.unaryLong(fn)
            if op == null then null else s"$jhd.mapLong((Object) ($refExpr), ${op.op}, ${op.c}L)"
          case _ => null
      case "flatMap" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val name = JitHofShape.globalLong(fn)
            if name == null then null
            else
              // Fuse a preceding `.map(unary)` so its Option/Either wrapper is not allocated.
              peelMapUnary(recv) match
                case (inner, op) =>
                  val innerRef = walkRef(inner, ctx)
                  if innerRef == null then s"""$jhd.flatMapGlobalLong((Object) ($refExpr), "${escape(name)}")"""
                  else s"""$jhd.mapFlatMapGlobalLong((Object) ($innerRef), ${op.op}, ${op.c}L, "${escape(name)}")"""
                case null =>
                  s"""$jhd.flatMapGlobalLong((Object) ($refExpr), "${escape(name)}")"""
          case _ => null
      case "filter" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val pred = JitHofShape.predicateLong(fn)
            if pred == null then null
            else s"$jhd.filterLong((Object) ($refExpr), ${pred.pred}, ${pred.c1}L, ${pred.c2}L)"
          case _ => null
      case _ => null

  private def emitHofFoldLong(recv: Term, method: String, args: List[Term], ctx: GenCtx): String | Null =
    if method != "fold" || args.lengthCompare(2) != 0 then return null
    (args.head, args(1)) match
      case (left: Term.Function, right: Term.Function) =>
        val leftConst = JitHofShape.constantLong(left)
        val rightOp   = JitHofShape.unaryLong(right)
        if leftConst == null || rightOp == null then return null
        val refExpr = walkRef(recv, ctx)
        if refExpr == null then return null
        s"scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.foldLong((Object) ($refExpr), ${leftConst.longValue}L, ${rightOp.op}, ${rightOp.c}L)"
      case _ => null

  private def emitHofFoldLeftLong(inner: Term.Apply, outerArgs: List[Term], ctx: GenCtx): String | Null =
    if outerArgs.lengthCompare(1) != 0 then return null
    inner.fun match
      case Term.Select(recv: Term, Term.Name("foldLeft")) if inner.argClause.values.lengthCompare(1) == 0 =>
        outerArgs.head match
          case fn: Term.Function if JitHofShape.foldAdd(fn) =>
            val init = walkLong(inner.argClause.values.head, ctx)
            if init == null then return null
            // Loop fusion: collapse recv = base.map(f).filter(g) into one pass.
            val fused = emitFusedFoldChain(recv, init, ctx)
            if fused != null then return fused
            val refExpr = walkRef(recv, ctx)
            if refExpr == null then return null
            s"scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.foldLeftLong((Object) ($refExpr), $init, ${JitHofDispatch.FoldAdd})"
          case _ => null
      case _ => null

  /** Emit a single fused fold call for `base.map(f).filter(g)` (either stage
   *  optional) as the receiver of a `foldLeft(init)(+)`. When `base` is an integer
   *  range, iterates it with a primitive counter (`fusedRangeFoldLong`, no base
   *  list); otherwise walks a list receiver (`fusedFoldLong`). Returns null when
   *  the chain is not fusable (caller falls back to the per-stage path). */
  private def emitFusedFoldChain(recv: Term, init: String, ctx: GenCtx): String | Null =
    val chain = JitHofShape.fuseFoldChain(recv)
    val base = if chain != null then chain.base else recv
    val mp   = if chain != null then chain.map else null
    val ft   = if chain != null then chain.filter else null
    val hasMap    = mp != null
    val mapOp     = if hasMap then mp.op else 0
    val mapC      = if hasMap then mp.c  else 0L
    val hasFilter = ft != null
    val pred      = if hasFilter then ft.pred else 0
    val fc1       = if hasFilter then ft.c1   else 0L
    val fc2       = if hasFilter then ft.c2   else 0L
    val ops = s"$hasMap, $mapOp, ${mapC}L, $hasFilter, $pred, ${fc1}L, ${fc2}L, $init, ${JitHofDispatch.FoldAdd}"
    val jhd = "scalascript.interpreter.vm.jit.JitHofDispatch$.MODULE$"
    // Range-native fusion: no materialised base list (covers a bare range fold too).
    val rb = JitHofShape.rangeBounds(base)
    if rb != null then
      val lo = walkLong(rb.lo, ctx)
      val hi = walkLong(rb.hi, ctx)
      if lo == null || hi == null then return null
      val until = if rb.inclusive then s"(($hi) + 1L)" else s"($hi)"
      return s"$jhd.fusedRangeFoldLong((long)($lo), (long)($until), $ops)"
    // List receiver fusion only pays off when there is a map/filter stage to
    // collapse; a bare `list.foldLeft` is already materialised — leave it.
    if chain == null then return null
    val refExpr = walkRef(base, ctx)
    if refExpr == null then return null
    s"$jhd.fusedFoldLong((Object) ($refExpr), $ops)"

  private def emitBuiltinEitherObject(typeName: String, arg: Term, ctx: GenCtx): String | Null =
    if typeName != "Right" && typeName != "Left" then return null
    val value = emitValueObject(arg, ctx)
    if value == null then null
    else s"""scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.eitherValue("$typeName", $value)"""

  /** Emit a Java expression reading InstanceV field `obj.field` off a ref param,
   *  yielding a primitive (`long` if !wantDouble, `double` if wantDouble).
   *  Resolves the field index from the param's declared type via `typeFieldOrder`,
   *  reads `fieldsArr[idx]` with a `fields().apply(name)` Map fallback, and unboxes.
   *  Returns null (caller bails to the interpreter) when the type/field is unknown
   *  or the field's primitive kind does not match `wantDouble`. */
  private def emitRefFieldNumeric(objName: String, field: String, ctx: GenCtx,
                                  wantDouble: Boolean): String | Null =
    val tn = ctx.refTypeOf(objName)
    if tn == null then return null
    val fo  = ctx.interp.typeFieldOrder.getOrElse(tn, Nil)
    val idx = fo.indexOf(field)
    if idx < 0 then return null
    val ft    = ctx.interp.typeFieldTypes.getOrElse(tn, Nil)
    val ftype = if idx < ft.length then ft(idx) else ""
    if wantDouble then { if ftype != "Double" then return null }
    else if ftype != "Int" && ftype != "Long" then return null
    val jvar    = ctx.resolveLocal(objName)
    if jvar == null then return null
    val wrapper = if wantDouble then "scalascript.interpreter.DataValue.DoubleV"
                  else              "scalascript.interpreter.DataValue.IntV"
    val inst    = s"((scalascript.interpreter.Value$$package$$Value$$InstanceV) $jvar)"
    val arr     = s"$inst.fieldsArr()[$idx]"
    val mapVal  = s"""$inst.fields().apply("${escape(field)}")"""
    val raw     = s"($inst.fieldsArr() != null ? $arr : $mapVal)"
    s"((($wrapper) $raw).v())"

  /** Emit a Java `Object`-typed expression. Handles ref params, 1-stmt blocks,
   *  and ref-typed ADT field access (`obj.field` where field is non-numeric). */
  private def walkRef(t: Term, ctx: GenCtx): String | Null = t match
    case Term.Name("None") => "scalascript.interpreter.Value$package.Value$.MODULE$.NoneV()"
    // Stage 8: String literal as ref-typed value.
    case Lit.String(v) => s"""scalascript.interpreter.Value$$package.Value$$.MODULE$$.StringV().apply("${escape(v)}")"""
    // Stage 8: builtin empty collections.
    case Term.Name("Nil") =>
      "scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$.NilRef()"
    // Stage 8: List(...) / Set(...) / Map(...) builtin constructors.
    // Accept both `Map(...)` and `Map[K, V](...)` (type application wrapped).
    case Term.Apply.After_4_6_0(funExpr, argClause)
        if {
          val name = funExpr match
            case Term.Name(n) => n
            case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
            case _ => ""
          name == "List" || name == "Set" || name == "Map" || name == "Array" || name == "Vector"
        } =>
      val ctorName = funExpr match
        case Term.Name(n) => n
        case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
        case _ => ""
      val helper = ctorName match
        case "List"   => "buildListRef"
        case "Set"    => "buildSetRef"
        case "Map"    => "buildMapRef"
        case "Array"  => "buildArrayRef"
        case "Vector" => "buildVectorRef"
      val args = argClause.values
      val sb = new StringBuilder
      sb.append("scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$.")
      sb.append(helper)
      sb.append("(new Object[] { ")
      var i = 0
      var ok = true
      while i < args.length && ok do
        if i > 0 then sb.append(", ")
        val v = emitValueObject(args(i), ctx)
        if v == null then ok = false
        else sb.append(s"(Object) ($v)")
        i += 1
      if !ok then null
      else
        sb.append(" })")
        sb.toString
    // T3: case-class / ADT constructor `Vec(a, b)` → InstanceV via JitRefDispatch.
    // Discriminated from sibling-function calls by membership in `typeFieldOrder`
    // (only registered case classes / enum cases carry a positional field order).
    // Each field Value is emitted via emitValueObject (numeric→intV, ref→walkRef);
    // names are inlined (no statics dependency, unlike the body-return __inst path).
    case Term.Apply.After_4_6_0(funExpr, argClause)
        if {
          val nm = funExpr match
            case Term.Name(n) => n
            case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
            case _ => ""
          nm.nonEmpty && !builtinAdtCtorNames.contains(nm) && ctx.interp.typeFieldOrder.contains(nm)
        } =>
      val typeName = funExpr match
        case Term.Name(n) => n
        case Term.ApplyType.After_4_6_0(Term.Name(n), _) => n
        case _ => ""
      val fieldNames = ctx.interp.typeFieldOrder.getOrElse(typeName, Nil)
      val args = argClause.values
      if fieldNames.lengthCompare(args.length) != 0 then null
      else
        val parts = new Array[String](args.length)
        var i = 0; var ok = true
        while i < args.length && ok do
          val v = emitValueObject(args(i), ctx)
          if v == null then ok = false else parts(i) = v
          i += 1
        if !ok then null
        else
          val tag = ctx.interp.typeTagMap.getOrElse(typeName, 0)
          val arr = parts.mkString("new Object[] { ", ", ", " }")
          val nms = fieldNames.map(fn => "\"" + escape(fn) + "\"").mkString("new String[] { ", ", ", " }")
          s"""scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.newInstanceRef("${escape(typeName)}", $tag, $arr, $nms)"""
    // T3: tuple literal `(a, b, …)` → TupleV via JitRefDispatch.newTupleRef.
    case Term.Tuple(elems) =>
      val parts = new Array[String](elems.length)
      var i = 0; var ok = true
      val it = elems.iterator
      while it.hasNext && ok do
        val v = emitValueObject(it.next(), ctx)
        if v == null then ok = false else { parts(i) = v; i += 1 }
      if !ok then null
      else
        val arr = parts.mkString("new Object[] { ", ", ", " }")
        s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.newTupleRef($arr)"
    // Stage 8: s"prefix${arg1}mid${arg2}suffix" — emit as StringV(string-concat).
    // Each arg compiled via walkLong (numeric → direct String concat) or walkRef
    // (ref → wrapped in Value.show). Only the `s` interpolator is supported here;
    // f/md/html/css are deferred to the tree-walker.
    case Term.Interpolate(Term.Name("s"), parts, args) if parts.lengthCompare(args.length + 1) == 0 =>
      val sb = new StringBuilder
      sb.append("scalascript.interpreter.Value$package.Value$.MODULE$.StringV().apply(")
      var idx = 0
      var first = true
      while idx < parts.length do
        val part = parts(idx) match
          case ls: Lit.String => ls.value
          case _              => return null
        if !first then sb.append(" + ")
        sb.append("\"").append(escape(part)).append("\"")
        first = false
        if idx < args.length then
          val arg = args(idx).asInstanceOf[Term]
          val asLong = walkLong(arg, ctx)
          if asLong != null then
            sb.append(" + ").append(asLong)
          else
            val asRef = walkRef(arg, ctx)
            if asRef == null then return null
            sb.append(" + scalascript.interpreter.Value$package.Value$.MODULE$.show((" + asRef + "))")
        idx += 1
      sb.append(")")
      sb.toString
    // Stage 8 / T3: `xs ++ ys` — List/Map/Tuple concat via JitRefDispatch.collectionConcat.
    // Type-test form (not `.After_4_6_0`) so it matches the parser's actual
    // ApplyInfix node regardless of scala.meta version suffix.
    //
    // GOTCHA: the parser lowers `tupleA ++ (3, 4)` to `++` with a MULTI-arg
    // clause `[3, 4]` (the RHS tuple literal becomes positional args), not a
    // single tuple arg. So when nargs != 1 we rebuild the args into a TupleV;
    // nargs == 1 keeps the List/Map receiver-arg form.
    case ai: Term.ApplyInfix if ai.op.value == "++" =>
      val lhsRef = walkRef(ai.lhs, ctx)
      if lhsRef == null then null
      else
        val args = ai.argClause.values
        val rhsRef: String | Null =
          if args.lengthCompare(1) == 0 then walkRef(args.head, ctx)
          else
            val parts = args.map(a => emitValueObject(a, ctx))
            if parts.exists(_ == null) then null
            else parts.mkString("scalascript.interpreter.vm.jit.JitRefDispatch$.MODULE$.newTupleRef(new Object[] { ", ", ", " })")
        if rhsRef == null then null
        else
          s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.collectionConcat(" +
            s"(Object) ($lhsRef), (Object) ($rhsRef))"
    // Stage 8: BigInt/Decimal infix arithmetic — route to JitRefDispatch numeric helpers.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if isNumericObjectReceiver(lhs) && argClause.values.lengthCompare(1) == 0 =>
      val helper = (lhs, op.value) match
        case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "+")  => "bigIntPlus"
        case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "-")  => "bigIntMinus"
        case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "*")  => "bigIntTimes"
        case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "/")  => "bigIntDiv"
        case (Term.Apply.After_4_6_0(Term.Name("BigInt"), _), "%")  => "bigIntMod"
        case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "+") => "decimalPlus"
        case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "-") => "decimalMinus"
        case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "*") => "decimalTimes"
        case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "/") => "decimalDiv"
        case (Term.Apply.After_4_6_0(Term.Name("Decimal"), _), "%") => "decimalMod"
        case _ => null
      if helper == null then null
      else
        val recv = emitNumericObjectValue(lhs, ctx)
        val other = emitValueObject(argClause.values.head, ctx)
        if recv == null || other == null then null
        else s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.$helper((Object) ($recv), $other)"
    // Stage 8: `ref + x` where lhs is a ref expression (String/etc.) — emit
    // `new StringV(Value.show(lhs) + (Long-or-show(rhs)))`. Result is StringV.
    // Routes ApplyInfixRefOp `+` calls (string concat shape) through walkRef.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if op.value == "+" && argClause.values.lengthCompare(1) == 0 =>
      val lhsRef = walkRef(lhs, ctx)
      if lhsRef == null then return null
      val rhs = argClause.values.head
      val rhsExpr =
        val asLong = walkLong(rhs, ctx)
        if asLong != null then asLong
        else
          val asRef = walkRef(rhs, ctx)
          if asRef == null then return null
          s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.show(($asRef))"
      s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.StringV().apply(" +
        s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.show(($lhsRef)) " +
        s"+ $rhsExpr)"
    case tn: Term.Name if ctx.isRefName(tn.value) => ctx.resolveLocal(tn.value)
    case tn: Term.Name =>
      ctx.interp.globals.getOrElse(tn.value, null) match
        case _: Value.ListV | _: Value.VectorV | _: Value.ArrayV | _: Value.OptionV | _: Value.InstanceV | _: Value.MapV | _: Value.StringV =>
          s"""scalascript.interpreter.vm.jit.JitGlobals$$.MODULE$$.readGlobalRef("${escape(tn.value)}")"""
        case _ => null
    // Direction A.1: 1-stmt block unwrap parallel to walkLong.
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkRef(inner, ctx)
        case _           => null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if (op.value == "until" || op.value == "to") && argClause.values.lengthCompare(1) == 0 =>
      val from = walkLong(lhs, ctx)
      if from == null then return null
      val hi = walkLong(argClause.values.head, ctx)
      if hi == null then return null
      // `to` is inclusive: rangeUntil's upper bound is exclusive, so pass hi + 1.
      val until = if op.value == "to" then s"(($hi) + 1L)" else hi
      s"scalascript.interpreter.vm.jit.JitHofDispatch$$.MODULE$$.rangeUntil($from, $until)"
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" && ap.argClause.values.lengthCompare(1) == 0 =>
          val inner = emitValueObject(ap.argClause.values.head, ctx)
          if inner == null then null else s"new scalascript.interpreter.Value$$package$$Value$$OptionV($inner)"
        case fn: Term.Name if (fn.value == "Right" || fn.value == "Left") && ap.argClause.values.lengthCompare(1) == 0 =>
          emitBuiltinEitherObject(fn.value, ap.argClause.values.head, ctx)
        case fn: Term.Name =>
          emitRefCall(fn.value, ap.argClause.values, ctx)
        case Term.Select(recv: Term, Term.Name(method)) =>
          val hof = emitHofRefChain(recv, method, ap.argClause.values, ctx)
          if hof != null then hof else emitRefChainObject(recv, method, ap.argClause.values, ctx)
        case _ => null
    // Stage 8: no-paren method calls on ref expressions (e.g. `s.trim`,
    // `s.toUpperCase`) — common in `s.trim.toInt` chains. Route through
    // emitRefChainObject which already handles the named methods.
    case Term.Select(recv: Term, Term.Name(method))
        if method == "trim" || method == "toUpperCase" || method == "toLowerCase" ||
           method == "toString" =>
      emitRefChainObject(recv, method, Nil, ctx)
    // Stage 5.5: ref-typed field access `obj.field` where obj is a ref param.
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      val tn = ctx.refTypeOf(objName)
      if tn == null then return null
      val fo = ctx.interp.typeFieldOrder.getOrElse(tn, Nil)
      val idx = fo.indexOf(field)
      if idx < 0 then return null
      val ft = ctx.interp.typeFieldTypes.getOrElse(tn, Nil)
      val ftype = if idx < ft.length then ft(idx) else ""
      if ftype == "Int" || ftype == "Long" || ftype == "Double" then return null
      val jvar = ctx.resolveLocal(objName)
      if jvar == null then return null
      val ivar = s"((scalascript.interpreter.Value$$package$$Value$$InstanceV) $jvar)"
      val arr  = s"$ivar.fieldsArr()[$idx]"
      val map  = s"""$ivar.fields().apply("${escape(field)}")"""
      s"($ivar.fieldsArr() != null ? $arr : $map)"
    case _ => null

  /** Emit a Java `Object` expression for pure ADT-building functions.
   *
   *  This intentionally narrow slice targets builder shapes such as:
   *
   *  `def build(d: Int): Expr =
   *     if d <= 0 then Num(1)
   *     else Add(build(d - 1), Mul(build(d - 1), Num(2)))`
   *
   *  It does not compile arbitrary object-valued ScalaScript. Supported forms
   *  are one-statement blocks, `if` expressions, self-recursive calls over
   *  long arguments, and registered case-class/enum constructors whose fields
   *  can be emitted as either primitive `Value` wrappers or nested object
   *  constructor expressions.
   */
  private def walkObject(
    t:       Term,
    ctx:     GenCtx,
    statics: scala.collection.mutable.LinkedHashMap[String, String]
  ): String | Null = t match
    case Term.Name("None") =>
      "scalascript.interpreter.Value$package.Value$.MODULE$.NoneV()"
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkObject(inner, ctx, statics)
        case _           => null
    case ti: Term.If =>
      val c = walkBool(ti.cond, ctx); if c == null then return null
      val a = walkObject(ti.thenp, ctx, statics); if a == null then return null
      val b = walkObject(ti.elsep, ctx, statics); if b == null then return null
      s"(($c) ? ($a) : ($b))"
    case Term.Select(recv: Term, Term.Name(method)) if isNumericObjectReceiver(recv) =>
      emitNumericObjectMethod(recv, method, Nil, ctx)
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" && ap.argClause.values.lengthCompare(1) == 0 =>
          val inner = emitValueObject(ap.argClause.values.head, ctx)
          if inner == null then null
          else s"new scalascript.interpreter.Value$$package$$Value$$OptionV($inner)"
        case fn: Term.Name if (fn.value == "Right" || fn.value == "Left") && ap.argClause.values.lengthCompare(1) == 0 =>
          emitBuiltinEitherObject(fn.value, ap.argClause.values.head, ctx)
        case fn: Term.Name if fn.value == ctx.funName =>
          if ap.argClause.values.length != ctx.paramNames.length then return null
          val args = new Array[String](ap.argClause.values.length)
          var i = 0
          var rem = ap.argClause.values
          while rem.nonEmpty do
            val e = walkLong(rem.head, ctx)
            if e == null then return null
            args(i) = e
            i += 1
            rem = rem.tail
          s"${sanitize(ctx.funName)}(${args.mkString(", ")})"
        case ctor: Term.Name if ctx.interp.typeFieldOrder.contains(ctor.value) =>
          emitConstructorObject(ctor.value, ap.argClause.values, ctx, statics)
        case ctor: Term.Name if ctor.value == "BigInt" || ctor.value == "Decimal" =>
          emitNumericObjectValue(ap, ctx)
        case Term.Select(recv: Term, Term.Name(method)) if isNumericObjectReceiver(recv) =>
          emitNumericObjectMethod(recv, method, ap.argClause.values, ctx)
        case _ if objectRefFallbackAllowed(ap, ctx) =>
          val r = walkRef(ap, ctx)
          if r == null then null else s"(Object) ($r)"
        case _ => null
    case _ if objectRefFallbackAllowed(t, ctx) =>
      val r = walkRef(t, ctx)
      if r == null then null else s"(Object) ($r)"
    case _ => null

  private def emitValueObject(
    t:       Term,
    ctx:     GenCtx
  ): String | Null =
    if isNumericObjectValueShape(t) then
      val numeric = emitNumericObjectValue(t, ctx)
      if numeric == null then return null
      return s"(Object) ($numeric)"
    t match
      case Lit.Boolean(_) =>
        val b = walkBool(t, ctx); if b == null then null
        else s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.boolV($b)"
      case _ =>
        val l = walkLong(t, ctx)
        if l != null then s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.intV($l)"
        else
          val s = walkString(t, ctx)
          if s != null then s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.StringV().apply($s)"
          else
            val r = walkRef(t, ctx)
            if r != null then s"(Object) ($r)"
            else null

  private def emitConstructorObject(
    typeName: String,
    args:     List[Term],
    ctx:      GenCtx,
    statics:  scala.collection.mutable.LinkedHashMap[String, String]
  ): String | Null =
    val fieldNames = ctx.interp.typeFieldOrder.getOrElse(typeName, Nil)
    if fieldNames.length != args.length then return null
    val fieldTypes = ctx.interp.typeFieldTypes.getOrElse(typeName, Nil)
    val values = new Array[String](args.length)
    var i = 0
    var rest = args
    while rest.nonEmpty do
      val ft = if i < fieldTypes.length then fieldTypes(i) else ""
      val v =
        if isPrimitiveFieldType(ft) then emitPrimitiveValue(rest.head, ft, ctx)
        else
          val obj = walkObject(rest.head, ctx, statics)
          if obj == null then null else s"(Object) ($obj)"
      if v == null then return null
      values(i) = v
      i += 1
      rest = rest.tail
    val namesField = constructorNamesField(typeName, fieldNames, statics)
    val tag = ctx.interp.typeTagMap.getOrElse(typeName, 0)
    val arr =
      if values.isEmpty then "new Object[0]"
      else values.mkString("new Object[] { ", ", ", " }")
    s"""__inst("${escape(typeName)}", $tag, $arr, $namesField)"""

  private def isPrimitiveFieldType(t: String): Boolean =
    t == "Int" || t == "Long" || t == "Double" || t == "Boolean" || t == "String"

  private def emitPrimitiveValue(t: Term, fieldType: String, ctx: GenCtx): String | Null =
    fieldType match
      case "Int" | "Long" =>
        val e = walkLong(t, ctx); if e == null then null
        else s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.intV($e)"
      case "Double" =>
        val e = walkDouble(t, ctx); if e == null then null
        else s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.doubleV($e)"
      case "Boolean" =>
        val e = walkBool(t, ctx); if e == null then null
        else s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.boolV($e)"
      case "String" =>
        val e = walkString(t, ctx); if e == null then null
        else s"scalascript.interpreter.Value$$package.Value$$.MODULE$$.StringV().apply($e)"
      case _ => null

  private def constructorNamesField(
    typeName:    String,
    fieldNames:  List[String],
    statics:     scala.collection.mutable.LinkedHashMap[String, String]
  ): String =
    val key = "__names_" + sanitize(typeName)
    if !statics.contains(key) then
      val elems = fieldNames.map(n => "\"" + escape(n) + "\"").mkString(", ")
      statics(key) = s"  private static final String[] $key = new String[] { $elems };\n"
    key

  /** Stage 8: match-guard expression compilation with Long-fallback (mirrors
   *  stage-6 bool-body-ext for guards). Tries `walkBool` first; on failure
   *  falls back to `walkLong` wrapped as `(longExpr) != 0L`. Returns a Java
   *  expression of type `boolean` or `null` if both walkers bail. */
  private def guardBoolExpr(t: Term, ctx: GenCtx): String | Null =
    val asBool = walkBool(t, ctx)
    if asBool != null then asBool
    else
      val asLong = walkLong(t, ctx)
      if asLong != null then s"(($asLong) != 0L)" else null

  private def walkBool(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Boolean(b) => if b then "true" else "false"
    case Term.ApplyUnary(op, arg) if op.value == "!" =>
      val a = walkBool(arg, ctx)
      if a == null then null else s"(!$a)"
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkBool(inner, ctx)
        case _           => null
    case ti: Term.If =>
      val c = walkBool(ti.cond, ctx);  if c == null then return null
      val a = walkBool(ti.thenp, ctx); if a == null then return null
      val bv = walkBool(ti.elsep, ctx); if bv == null then return null
      s"(($c) ? ($a) : ($bv))"
    case tn: Term.Name if !ctx.isRefName(tn.value) =>
      // Bool-typed local or param: encoded as 0L/1L — treat `!= 0L` as the bool.
      val local = ctx.resolveLocal(tn.value)
      if local != null then s"($local != 0L)" else null
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name =>
          // Call to a bool-returning sibling co-emitted as `static long`; unwrap
          // by checking `!= 0L`.
          val sig = ensureCoEmittedLong(fn.value, ctx)
          if sig != null && !sig.isDouble && ap.argClause.values.length == sig.paramNames.length then
            val sb = new StringBuilder
            sb.append(sanitize(fn.value)).append('(')
            var i = 0; var rem = ap.argClause.values
            while rem.nonEmpty do
              if i > 0 then sb.append(", ")
              val argStr = if sig.paramIsRef(i) then walkRef(rem.head, ctx) else walkLong(rem.head, ctx)
              if argStr == null then return null
              sb.append(argStr); i += 1; rem = rem.tail
            sb.append(')')
            s"(${sb.toString()} != 0L)"
          else
            // HOF param call in boolean context: `f(n) != 0L`
            val e = emitLongCall(fn.value, ap.argClause.values, ctx)
            if e != null then s"($e != 0L)" else null
        case _ => null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      val opStr = op.value
      opStr match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          // Use walkDouble for double-typed funcs, walkLong otherwise. Comparison
          // operands are typed-by-context the same as the rest of the body.
          val w: (Term, GenCtx) => String | Null = if ctx.isDouble then walkDouble else walkLong
          val l = w(lhs, ctx)
          if l != null then
            val r = w(argClause.values.head, ctx); if r == null then return null
            s"($l $opStr $r)"
          else if opStr == "==" || opStr == "!=" then
            // Stage 8: ref ==/!= ref — Objects.equals fallback (case-class structural
            // equality on Value subtypes: StringV, InstanceV, ListV, OptionV, ...).
            val lr = walkRef(lhs, ctx)
            if lr == null then return null
            val rr = walkRef(argClause.values.head, ctx)
            if rr == null then return null
            val eq = s"java.util.Objects.equals((Object) ($lr), (Object) ($rr))"
            if opStr == "==" then s"($eq)" else s"(!$eq)"
          else null
        case "&&" | "||" =>
          val l = walkBool(lhs, ctx); if l == null then return null
          val r = walkBool(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case _ => null
    case other =>
      // General fallback: any Long-compilable expression where non-zero means true.
      // This handles bool-returning match expressions, Lit.Boolean (via walkLong),
      // and other Long-valued sub-expressions used in boolean position.
      val e = walkLong(other, ctx)
      if e != null then s"($e != 0L)" else null

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
    // `deeperMatch`: see walkLong — keeps nested-match helper locals distinct.
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx.deeperMatch, ctx.interp)
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
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      emitRefFieldNumeric(objName, field, ctx, wantDouble = true)
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
        // Stage 8: Math.sqrt/floor/ceil/log/exp/abs (1-arg) and Math.pow/max/min (2-arg).
        case Term.Select(Term.Name("Math"), Term.Name(method))
            if (method == "sqrt" || method == "floor" || method == "ceil" ||
                method == "log" || method == "log10" || method == "exp" ||
                method == "abs" || method == "sin" || method == "cos" || method == "tan")
              && ap.argClause.values.lengthCompare(1) == 0 =>
          val a = walkDouble(ap.argClause.values.head, ctx)
          if a == null then null else s"java.lang.Math.$method($a)"
        case Term.Select(Term.Name("Math"), Term.Name(method))
            if (method == "pow" || method == "max" || method == "min" || method == "atan2")
              && ap.argClause.values.lengthCompare(2) == 0 =>
          val a = walkDouble(ap.argClause.values.head, ctx)
          val b = walkDouble(ap.argClause.values(1), ctx)
          if a == null || b == null then null else s"java.lang.Math.$method($a, $b)"
        case _ => null
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkDouble(arg, ctx); if a == null then return null
      s"(${op.value}$a)"
    case _ => null

  /** True iff every arm is Pat.Tuple/Var/Wildcard and at least one is Pat.Tuple. */
  private def isTupleMatch(tm: Term.Match): Boolean =
    JitPredicates.isTupleMatch(tm)

  /** Emit body-statement form for a match where all arms are Pat.Tuple/Wildcard/Var.
   *  Casts the scrutinee to TupleV, accesses elems() list, extracts by index. */
  private def walkTupleMatchBody(
    tm: Term.Match, ctx: GenCtx, @annotation.unused _interp: scalascript.interpreter.Interpreter
  ): String | Null =
    val (scrutDecl, scrutJava) = tm.expr match
      case n: Term.Name =>
        if !ctx.params.contains(n.value) then return null
        ("", sanitize(n.value))
      case other =>
        val refExpr = walkRef(other, ctx)
        if refExpr == null then return null
        (s"Object _scrutRef = $refExpr;\n    ", "_scrutRef")
    val cases     = tm.casesBlock.cases
    val tupleCnt  = cases.count(_.pat.isInstanceOf[Pat.Tuple])
    val multiTuple = tupleCnt > 1
    val sb = new StringBuilder
    sb.append(scrutDecl)
    sb.append(s"scala.collection.immutable.List _telems = (scala.collection.immutable.List) ((scalascript.interpreter.Value$$package$$Value$$TupleV) $scrutJava).elems();\n    ")
    var restList = cases
    var hasDefault = false
    while restList.nonEmpty do
      val c = restList.head
      restList = restList.tail
      c.pat match
        case pt: Pat.Tuple =>
          val patsArr = pt.args.toArray
          val n = patsArr.length
          if multiTuple then sb.append(s"if (_telems.length() == $n) {\n    ")
          val bindNames = new Array[String](n)
          var i = 0; var ok = true
          while i < n && ok do
            patsArr(i) match
              case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
              case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
              case _                      => ok = false
            i += 1
          if !ok then return null
          val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
          var k = 0
          while k < n do
            if !bindNames(k).startsWith("_unused$") then
              val jvar  = sanitize(bindNames(k)) + "_a"
              val isRef = bindingIsRef(c.body, bindNames(k), ctx)
              bindingMap += (bindNames(k) -> (jvar, isRef))
            k += 1
          val newCtx = ctx.withBindings(bindingMap.toMap)
          k = 0
          while k < n do
            if !bindNames(k).startsWith("_unused$") then
              val (jvar, isRef) = bindingMap(bindNames(k))
              val elem = s"_telems.apply($k)"
              if isRef then
                sb.append(s"Object $jvar = $elem;\n    ")
              else if ctx.isDouble then
                val raw = s"_rft${k}_"
                sb.append(s"Object $raw = $elem;\n    ")
                sb.append(s"double $jvar = $raw instanceof scalascript.interpreter.DataValue.DoubleV ? ((scalascript.interpreter.DataValue.DoubleV) $raw).v() : (double) ((scalascript.interpreter.DataValue.IntV) $raw).v();\n    ")
              else
                sb.append(s"long $jvar = ((scalascript.interpreter.DataValue.IntV) ($elem)).v();\n    ")
            k += 1
          if c.cond.nonEmpty then
            val guardJava = guardBoolExpr(c.cond.get, newCtx)
            if guardJava == null then return null
            sb.append(s"if ($guardJava) {\n    ")
          val bodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
          if bodyJava == null then return null
          if c.cond.nonEmpty then sb.append(s"return $bodyJava;\n    }\n    ")
          else                     sb.append(s"return $bodyJava;\n    ")
          if multiTuple then sb.append("}\n    ")
        case _: Pat.Wildcard =>
          hasDefault = true
          val bodyJava = if ctx.isDouble then walkDouble(c.body, ctx) else walkLong(c.body, ctx)
          if bodyJava == null then return null
          sb.append(s"return $bodyJava;\n    ")
        case pv: Pat.Var =>
          hasDefault = true
          val xName = pv.name.value
          val xBind = sanitize(xName) + "_a"
          val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
          val bodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
          if bodyJava == null then return null
          sb.append(s"Object $xBind = $scrutJava;\n    return $bodyJava;\n    ")
        case _ => return null
    // Only need a throw when there are multiple length-guarded tuple arms and no
    // default catch-all: a length-mismatch falls through to here.
    if multiTuple && !hasDefault then
      sb.append("throw new RuntimeException(\"JavacJitBackend: tuple match no case matched\");\n    ")
    sb.toString

  /** True when every arm is a literal-int pattern, Pat.Var, or Pat.Wildcard with
   *  no guards — the match compiles as a long-comparison if-else chain. */
  private def isLiteralIntMatch(tm: Term.Match): Boolean =
    JitPredicates.isLiteralIntMatch(tm)

  /** Emit a literal-integer match body as an if-else chain returning long/double.
   *  Scrutinee may be any `walkLong`-able expression, not just a `Term.Name`. */
  private def walkLiteralMatchBody(tm: Term.Match, ctx: GenCtx): String | Null =
    val scrutExpr = walkLong(tm.expr.asInstanceOf[Term], ctx)
    if scrutExpr == null then return null
    val scrVar = s"_scr${java.lang.Integer.toHexString(System.identityHashCode(tm))}"
    val sb  = new StringBuilder
    sb.append(s"long $scrVar = $scrutExpr;\n    ")
    var first = true
    val cases = tm.casesBlock.cases.toList
    var armRem = cases
    while armRem.nonEmpty do
      val arm = armRem.head; armRem = armRem.tail
      arm.pat match
        case _: Pat.Wildcard | _: Pat.Var =>
          val e = if ctx.isDouble then walkDouble(arm.body.asInstanceOf[Term], ctx)
                  else walkLong(arm.body.asInstanceOf[Term], ctx)
          if e == null then return null
          sb.append(s"else { return $e; }\n    ")
        case li: Lit =>
          val litStr = li match
            case Lit.Int(v)  => s"${v}L"
            case Lit.Long(v) => s"${v}L"
            case _           => return null
          val e = if ctx.isDouble then walkDouble(arm.body.asInstanceOf[Term], ctx)
                  else walkLong(arm.body.asInstanceOf[Term], ctx)
          if e == null then return null
          val kw = if first then "if" else "else if"
          sb.append(s"$kw ($scrVar == $litStr) { return $e; }\n    ")
          first = false
        case _ => return null
    val lastPat = cases.last.pat
    if !lastPat.isInstanceOf[Pat.Wildcard] && !lastPat.isInstanceOf[Pat.Var] then
      sb.append(s"""throw new RuntimeException("literal match: no arm matched");""")
    sb.toString

  /** Expression-context literal-int match: wraps the if-else chain in a
   *  `LongSupplier` IIFE to produce an expression value. */
  private def walkLiteralMatchExpr(tm: Term.Match, ctx: GenCtx): String | Null =
    val body = walkLiteralMatchBody(tm, ctx)
    if body == null then return null
    val supplierIface = if ctx.isDouble then "java.util.function.DoubleSupplier" else "java.util.function.LongSupplier"
    val getter        = if ctx.isDouble then "getAsDouble" else "getAsLong"
    s"(($supplierIface)(() -> {\n      $body\n    })).$getter()"

  private def walkMatchBody(tm: Term.Match, ctx: GenCtx, interp: scalascript.interpreter.Interpreter): String | Null =
    // Nested-match uniquifier: these helper locals are suffixed per match depth so
    // a nested match's IIFE body can't collide with this one (see GenCtx.nameSuffix).
    val sfx   = ctx.nameSuffix
    val instJ = "inst" + sfx
    val tnJ   = "tn" + sfx
    // Stage 5.5: support non-Term.Name scrutinees by hoisting to a local.
    val (scrutDecl, scrutJava) = tm.expr match
      case n: Term.Name =>
        if !ctx.params.contains(n.value) then return null
        ("", sanitize(n.value))
      case other =>
        val refExpr = walkRef(other, ctx)
        if refExpr == null then return null
        val scrutRefJ = "_scrutRef" + sfx
        (s"Object $scrutRefJ = $refExpr;\n    ", scrutRefJ)
    // Pre-resolve int tags for all arms so we can decide switch type upfront.
    val cases = tm.casesBlock.cases
    val armTags = new Array[Int](cases.length)
    var allTagged = true
    var ai = 0
    val casesArr = cases.toArray
    // A `case _: Supertype` arm (a type with descendants in `parentTypes`) cannot be
    // a switch on the exact type tag/name — it must runtime-test the parent chain.
    // Route the whole match through the if-chain path, where the typed arm emits a
    // `JitGlobals.isSubtype(...)` runtime check (busi seq-124; keeps it JIT-compiled
    // rather than bailing to tree-walk). Leaf-type type-tests still switch exactly.
    val hasSupertypeArm = casesArr.exists { c =>
      c.pat match
        case Pat.Typed(_, scala.meta.Type.Name(n)) => interp.parentTypes.valuesIterator.contains(n)
        case _                                      => false
    }
    while ai < casesArr.length do
      val ctorNameOpt = casesArr(ai).pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(n) => Some(n)
          case _            => None
        case Pat.Typed(_, scala.meta.Type.Name(n)) => Some(n)  // Stage 8: typed pattern
        case _ => None
      ctorNameOpt match
        case Some(cn) =>
          val tag = interp.typeTagMap.getOrElse(cn, 0)
          if tag == 0 then allTagged = false
          armTags(ai) = tag
        case None => allTagged = false
      ai += 1
    val sb = new StringBuilder
    sb.append(scrutDecl)
    sb.append(s"scalascript.interpreter.Value$$package$$Value$$InstanceV $instJ = (scalascript.interpreter.Value$$package$$Value$$InstanceV) $scrutJava;\n    ")
    // If any arm carries a guard, fall back to a sequential if-chain because a
    // Java switch can't re-dispatch on guard failure: when `case A(n) if n > 0`
    // fails its guard, execution must continue to the next arm rather than
    // falling through to the next switch case (which may have a different tag).
    val hasAnyGuard  = casesArr.exists(_.cond.nonEmpty)
    val hasAltPat    = casesArr.exists(_.pat.isInstanceOf[Pat.Alternative])
    val hasWildcard  = casesArr.exists(c => c.pat.isInstanceOf[Pat.Wildcard] || c.pat.isInstanceOf[Pat.Var])
    if hasAnyGuard || hasAltPat || hasSupertypeArm then
      var ci = 0
      var restList = cases
      while restList.nonEmpty do
        val arm = walkArmAsIfBranch(restList.head, ctx, interp, if allTagged then armTags(ci) else 0, allTagged)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail
        ci += 1
      if !hasWildcard then
        if allTagged then
          sb.append(s"throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + $instJ.typeTag());")
        else
          sb.append(s"throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + $instJ.typeName());")
    else
      if allTagged then
        sb.append(s"switch ($instJ.typeTag()) {\n    ")
      else
        sb.append(s"String $tnJ = $instJ.typeName();\n    ")
        sb.append(s"switch ($tnJ) {\n    ")
      var ci = 0
      var restList = cases
      while restList.nonEmpty do
        val arm = walkArm(restList.head, ctx, interp, if allTagged then armTags(ci) else 0)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail
        ci += 1
      if !hasWildcard then
        if allTagged then
          sb.append(s"  default: throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + $instJ.typeTag());\n    }")
        else
          sb.append(s"  default: throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + $tnJ);\n    }")
      else
        sb.append("}")
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
    if isLiteralIntMatch(tm) then return walkLiteralMatchExpr(tm, ctx)
    if isTupleMatch(tm) then
      val inner = walkTupleMatchBody(tm, ctx, interp)
      return if inner == null then null
             else s"((java.util.function.LongSupplier)(() -> { $inner})).getAsLong()"
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
        case Pat.Typed(_, scala.meta.Type.Name(n)) => Some(n)  // Stage 8: typed pattern
        case _ => None
      ctorNameOpt match
        case Some(cn) =>
          val tag = interp.typeTagMap.getOrElse(cn, 0)
          if tag == 0 then allTagged = false
          armTags(ai) = tag
        case None => allTagged = false
      ai += 1
    val sb = new StringBuilder
    // Nested-match uniquifier: this match compiles to an IIFE lambda, whose body
    // re-declares `inst`; Java forbids shadowing an enclosing-method local, so the
    // name is suffixed by depth (walkLong/walkDouble entered via `deeperMatch`).
    val instJ = "inst" + ctx.nameSuffix
    // We need `inst` accessible inside each arm. Wrap the switch expression
    // in a primitive-typed Supplier IIFE so the outer call site sees a plain
    // long/double — `java.util.function.{LongSupplier,DoubleSupplier}` avoid
    // the boxing that a `Function<T, Long>` would incur. HotSpot caches the
    // lambda via the LambdaMetafactory's stable callsite, so per-call cost is
    // a single virtual dispatch (~1 ns), small relative to the switch body.
    val supplierIface = if ctx.isDouble then "java.util.function.DoubleSupplier" else "java.util.function.LongSupplier"
    val getterMethod  = if ctx.isDouble then "getAsDouble"                       else "getAsLong"
    sb.append(s"(($supplierIface)(() -> {\n      ")
    sb.append(s"scalascript.interpreter.Value$$package$$Value$$InstanceV $instJ = (scalascript.interpreter.Value$$package$$Value$$InstanceV) $scrutJava;\n      ")
    val hasAnyGuard = casesArr.exists(_.cond.nonEmpty)
    // A supertype `case _: T` arm can't be an exact-tag switch — route to the
    // if-chain, where `walkArmAsIfBranch` emits a `JitGlobals.isSubtype` check
    // (busi seq-124, expression form).
    val hasSupertypeArm = casesArr.exists { c =>
      c.pat match
        case Pat.Typed(_, scala.meta.Type.Name(n)) => interp.parentTypes.valuesIterator.contains(n)
        case _                                      => false
    }
    val hasWildcardExpr = casesArr.exists(c => c.pat.isInstanceOf[Pat.Wildcard] || c.pat.isInstanceOf[Pat.Var])
    if hasAnyGuard || hasSupertypeArm then
      // Guard path: emit an if-chain inside the IIFE.
      // walkArmAsIfBranch emits `return body;` — inside the IIFE lambda this
      // returns from the lambda, producing the expression value.
      var ci = 0; var restList = cases
      while restList.nonEmpty do
        val arm = walkArmAsIfBranch(restList.head, ctx, interp,
                                    if allTagged then armTags(ci) else 0, allTagged)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail; ci += 1
      if !hasWildcardExpr then
        if allTagged then
          sb.append(s"throw new RuntimeException(\"JavacJitBackend: no guarded case matched, tag=\" + $instJ.typeTag());\n    ")
        else
          sb.append(s"throw new RuntimeException(\"JavacJitBackend: no guarded case matched, typeName=\" + $instJ.typeName());\n    ")
      sb.append("}))") // close lambda + IIFE cast
    else
      sb.append(s"return ")
      if allTagged then sb.append(s"switch ($instJ.typeTag()) {\n      ")
      else             sb.append(s"switch ($instJ.typeName()) {\n      ")
      var ci = 0; var restList = cases
      while restList.nonEmpty do
        val arm = walkArmExpr(restList.head, ctx, interp, if allTagged then armTags(ci) else 0)
        if arm == null then return null
        sb.append(arm)
        restList = restList.tail; ci += 1
      if !hasWildcardExpr then
        if allTagged then
          sb.append(s"  default -> { throw new RuntimeException(\"JavacJitBackend: no case matched, tag=\" + $instJ.typeTag()); }\n      };\n    }))")
        else
          sb.append(s"  default -> { throw new RuntimeException(\"JavacJitBackend: no case matched, typeName=\" + $instJ.typeName()); }\n      };\n    }))")
      else
        sb.append("};\n    }))")
    sb.append(s".$getterMethod()")
    sb.toString

  /** Expression-form parallel of `walkArm`: emits `case N -> { … yield …; }`
   *  arrow form for use inside a Java switch *expression*. The bindings and
   *  arm-body walking logic is the same as `walkArm`; only the framing
   *  differs (no `return`, must `yield`). */
  private def walkArmExpr(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int): String | Null =
    if c.cond.nonEmpty then return null
    val sfx   = ctx.nameSuffix   // nested-match uniquifier (see GenCtx.nameSuffix)
    val instJ = "inst" + sfx
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
            // An unreferenced named binding is treated as a wildcard so no field
            // is extracted (extracting an unused ref field as IntV would crash).
            case Pat.Var(Term.Name(bn)) => bindNames(i) = if bindingReferenced(c, bn) then bn else "_unused$" + i
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a" + sfx
            bindingMap += (bindNames(k) -> (jvar, bindIsRef(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        if intTag > 0 then sb.append(s"      case $intTag -> {\n        ")
        else               sb.append(s"""      case "${escape(ctorName)}" -> {\n        """)
        val faVar = s"__fa_${sanitize(ctorName)}$sfx"
        if n > 0 then sb.append(s"Object[] $faVar = $instJ.fieldsArr();\n        ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val readExpr = s"$faVar[$fi]"
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n        """)
            else if ctx.isDouble then
              val raw = s"_rf${fi}_${sanitize(ctorName)}$sfx"
              sb.append(s"""Object $raw = $readExpr;\n        """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.DataValue.DoubleV ? ((scalascript.interpreter.DataValue.DoubleV) $raw).v() : (double) ((scalascript.interpreter.DataValue.IntV) $raw).v();\n        """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.DataValue.IntV) ($readExpr)).v();\n        """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"yield $armBodyJava;\n      }\n    ")
        sb.toString
      case _: Pat.Wildcard =>
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, ctx) else walkLong(c.body, ctx)
        if armBodyJava == null then return null
        s"      default -> { yield $armBodyJava; }\n    "
      case pv: scala.meta.Pat.Var =>
        val xName = pv.name.value
        val xBind = sanitize(xName) + "_a" + sfx
        val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        s"      default -> { Object $xBind = $instJ; yield $armBodyJava; }\n    "
      case _ => null

  /** Emit a single match arm as an `if (tagCheck) { bindings; [if (guard) { ]return body;[ }] }`
   *  block. Used when any arm in the enclosing match has a guard — a Java switch
   *  can't re-dispatch on guard failure, so the whole match is lowered to an
   *  if-chain that naturally falls through to the next arm. */
  private def walkArmAsIfBranch(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int, allTagged: Boolean): String | Null =
    val sfx   = ctx.nameSuffix   // nested-match uniquifier (see GenCtx.nameSuffix)
    val instJ = "inst" + sfx
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
            // An unreferenced named binding is treated as a wildcard so no field
            // is extracted (extracting an unused ref field as IntV would crash).
            case Pat.Var(Term.Name(bn)) => bindNames(i) = if bindingReferenced(c, bn) then bn else "_unused$" + i
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a" + sfx
            bindingMap += (bindNames(k) -> (jvar, bindIsRef(k)))
          k += 1
        val newCtx = ctx.withBindings(bindingMap.toMap)
        val sb = new StringBuilder
        if allTagged && intTag > 0 then
          sb.append(s"if ($instJ.typeTag() == $intTag) {\n      ")
        else
          sb.append(s"""if ("${escape(ctorName)}".equals($instJ.typeName())) {\n      """)
        val faVar = s"__fa_${sanitize(ctorName)}$sfx"
        if n > 0 then sb.append(s"Object[] $faVar = $instJ.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val readExpr = s"$faVar[$fi]"
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n      """)
            else if ctx.isDouble then
              val raw = s"_rf${fi}_${sanitize(ctorName)}$sfx"
              sb.append(s"""Object $raw = $readExpr;\n      """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.DataValue.DoubleV ? ((scalascript.interpreter.DataValue.DoubleV) $raw).v() : (double) ((scalascript.interpreter.DataValue.IntV) $raw).v();\n      """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.DataValue.IntV) ($readExpr)).v();\n      """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        if c.cond.nonEmpty then
          val guardJava = guardBoolExpr(c.cond.get, newCtx)
          if guardJava == null then return null
          sb.append(s"if ($guardJava) { return $armBodyJava; }\n    ")
        else
          sb.append(s"return $armBodyJava;\n    ")
        sb.append("  }\n    ")
        sb.toString
      // `case _: T =>` / `case x: T =>` in the if-chain — a supertype `T` emits a
      // runtime `isSubtype` parent-chain check; a leaf `T` an exact tag/name check.
      case Pat.Typed(inner, scala.meta.Type.Name(typeName)) =>
        val bindName: String | Null = inner match
          case Pat.Var(Term.Name(bn)) => bn
          case _: Pat.Wildcard        => null
          case _                      => return null
        val isSuper = interp.parentTypes.valuesIterator.contains(typeName)
        val tag     = interp.typeTagMap.getOrElse(typeName, 0)
        val cond =
          if isSuper        then s"""scalascript.interpreter.vm.jit.JitGlobals.isSubtype($instJ.typeName(), "${escape(typeName)}")"""
          else if tag > 0   then s"$instJ.typeTag() == $tag"
          else                   s""""${escape(typeName)}".equals($instJ.typeName())"""
        val newCtx =
          if bindName == null then ctx
          else ctx.withBindings(Map(bindName -> (sanitize(bindName) + "_a" + sfx, true)))
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        val sb = new StringBuilder
        sb.append(s"if ($cond) {\n      ")
        if bindName != null then sb.append(s"Object ${sanitize(bindName)}_a$sfx = $instJ;\n      ")
        c.cond match
          case Some(g) =>
            val guardJava = guardBoolExpr(g, newCtx)
            if guardJava == null then return null
            sb.append(s"if ($guardJava) { return $armBodyJava; }\n      ")
          case None =>
            sb.append(s"return $armBodyJava;\n      ")
        sb.append("  }\n    ")
        sb.toString
      case _: Pat.Wildcard =>
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, ctx) else walkLong(c.body, ctx)
        if armBodyJava == null then return null
        s"return $armBodyJava;\n    "
      case pv: scala.meta.Pat.Var =>
        val xName = pv.name.value
        val xBind = sanitize(xName) + "_a" + sfx
        val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        s"Object $xBind = $instJ;\n    return $armBodyJava;\n    "
      case alt: Pat.Alternative =>
        // Pat.Alternative: emit `if (cond1 || cond2) { return body; }`.
        // Only supported when sub-patterns have no extracted field bindings used in the body.
        def altCond(p: scala.meta.Pat): String | Null = p match
          case ext: scala.meta.Pat.Extract =>
            val cn = ext.fun match { case Term.Name(n) => n; case _ => return null }
            if !ext.argClause.values.forall(_.isInstanceOf[Pat.Wildcard]) then return null
            val tag = interp.typeTagMap.getOrElse(cn, 0)
            if tag > 0 then s"$instJ.typeTag() == $tag"
            else s"""$instJ.typeName().equals("${escape(cn)}")"""
          case _: Pat.Wildcard => "true"
          case _: Pat.Var      => "true"
          case _ => null
        val lCond = altCond(alt.lhs); if lCond == null then return null
        val rCond = altCond(alt.rhs); if rCond == null then return null
        val cond = if rCond == "true" then lCond else if lCond == "true" then rCond else s"($lCond || $rCond)"
        val guardStr: String = c.cond match
          case None => ""
          case Some(g) => val gs = walkBool(g, ctx); if gs == null then return null; s" && $gs"
        val armBodyJava = if ctx.isDouble then walkDouble(c.body.asInstanceOf[Term], ctx)
                          else walkLong(c.body.asInstanceOf[Term], ctx)
        if armBodyJava == null then return null
        if c.cond.isEmpty then s"if ($cond) { return $armBodyJava; }\n    "
        else                     s"if ($cond$guardStr) { return $armBodyJava; }\n    "
      case _ => null

  /** `intTag > 0` means emit `case $intTag:` (int switch); `intTag == 0` means
   *  emit `case "CtorName":` (String switch fallback). */
  private def walkArm(c: scala.meta.Case, ctx: GenCtx, interp: scalascript.interpreter.Interpreter, intTag: Int): String | Null =
    if c.cond.nonEmpty then return null   // guards not supported in initial slice
    val sfx   = ctx.nameSuffix   // nested-match uniquifier (see GenCtx.nameSuffix)
    val instJ = "inst" + sfx
    c.pat match
      // Stage 8: `case _: T =>` and `case x: T =>` — type-test pattern over an
      // ADT constructor T. Equivalent to switch-case on T with no field bindings;
      // x (if present) binds the scrutinee as a ref-typed local.
      case Pat.Typed(inner, scala.meta.Type.Name(typeName)) =>
        val bindName: String | Null = inner match
          case Pat.Var(Term.Name(bn)) => bn
          case _: Pat.Wildcard        => null
          case _                      => return null
        val sb = new StringBuilder
        if intTag > 0 then sb.append(s"""  case $intTag: {\n      """)
        else               sb.append(s"""  case "${escape(typeName)}": {\n      """)
        val newCtx =
          if bindName == null then ctx
          else
            val bind = sanitize(bindName) + "_a" + sfx
            sb.append(s"Object $bind = $instJ;\n      ")
            ctx.withBindings(Map(bindName -> (bind, true)))
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"return $armBodyJava;\n      }\n    ")
        sb.toString
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
            // An unreferenced named binding is treated as a wildcard so no field
            // is extracted (extracting an unused ref field as IntV would crash).
            case Pat.Var(Term.Name(bn)) => bindNames(i) = if bindingReferenced(c, bn) then bn else "_unused$" + i
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx)
          bi += 1
        val bindingMap = scala.collection.mutable.LinkedHashMap.empty[String, (String, Boolean)]
        var k = 0
        while k < n do
          if !bindNames(k).startsWith("_unused$") then
            val jvar = sanitize(bindNames(k)) + "_a" + sfx
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
        val faVar = s"__fa_${sanitize(ctorName)}$sfx"
        if n > 0 then
          sb.append(s"Object[] $faVar = $instJ.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val readExpr = s"$faVar[$fi]"
            if isRef then
              sb.append(s"""Object $jvar = $readExpr;\n      """)
            else if ctx.isDouble then
              // Flexible DoubleV/IntV extraction for double-returning functions.
              val raw = s"_rf${fi}_${sanitize(ctorName)}$sfx"
              sb.append(s"""Object $raw = $readExpr;\n      """)
              sb.append(s"""double $jvar = $raw instanceof scalascript.interpreter.DataValue.DoubleV ? ((scalascript.interpreter.DataValue.DoubleV) $raw).v() : (double) ((scalascript.interpreter.DataValue.IntV) $raw).v();\n      """)
            else
              sb.append(s"""long $jvar = ((scalascript.interpreter.DataValue.IntV) ($readExpr)).v();\n      """)
          fi += 1
        val armBodyJava =
          if ctx.isDouble then walkDouble(c.body, newCtx)
          else              walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"return $armBodyJava;\n      }\n    ")
        sb.toString
      case _: Pat.Wildcard =>
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, ctx) else walkLong(c.body, ctx)
        if armBodyJava == null then return null
        s"  default: { return $armBodyJava; }\n    "
      case pv: scala.meta.Pat.Var =>
        val xName = pv.name.value
        val xBind = sanitize(xName) + "_a" + sfx
        val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
        val armBodyJava = if ctx.isDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        s"  default: { Object $xBind = $instJ;\n      return $armBodyJava;\n    }\n    "
      case _ => null

  /** Try to inline the match body of `funV` as a Java `switch (inst.typeTag())`
   *  block that accumulates into `_acc` directly — zero virtual dispatch.
   *  Returns the switch block string (caller prefixes `Value.InstanceV inst = cast(elem);`)
   *  or null if the function is not a 1-param, guard-free, fully int-tagged match. */
  private def tryBuildInlineMatchAccum(
    funV:        Value.FunV,
    accIsDouble: Boolean,
    interp:      scalascript.interpreter.Interpreter,
    targetVar:   String = "_acc"
  ): String | Null =
    if funV.params.length != 1 then return null
    val paramName = funV.params.head
    val tm = funV.body match
      case m: Term.Match => m
      case _             => return null
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return null
    if scrutName != paramName then return null
    val casesArr = tm.casesBlock.cases.toArray
    if casesArr.exists(_.cond.nonEmpty) then return null
    val armTags = new Array[Int](casesArr.length)
    var ai = 0
    while ai < casesArr.length do
      casesArr(ai).pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(cn) =>
            val tag = interp.typeTagMap.getOrElse(cn, 0)
            if tag == 0 then return null
            armTags(ai) = tag
          case _ => return null
        case _: Pat.Wildcard => // wildcard OK as last arm
        case _: Pat.Var      => // var OK as last arm
        case _               => return null
      ai += 1
    val paramSet  = Set(paramName)
    val paramArr  = Array(paramName)
    val paramIsRef = Array(true)
    val coEmit    = new CoEmitState
    coEmit.signatures.put("__inlineMatch", MethodSig(paramArr, paramIsRef, accIsDouble))
    coEmit.emitted.add("__inlineMatch")
    val ctx = new GenCtx("__inlineMatch", paramSet, paramArr, paramIsRef, accIsDouble, Map.empty, interp, coEmit)
    val sb = new StringBuilder
    sb.append("switch (inst.typeTag()) {\n")
    ai = 0
    while ai < casesArr.length do
      val armStr = walkArmForAccum(casesArr(ai), ctx, interp, armTags(ai), accIsDouble, targetVar)
      if armStr == null then return null
      sb.append(armStr)
      ai += 1
    val hasWildcard = casesArr.lastOption.exists(c => c.pat.isInstanceOf[Pat.Wildcard] || c.pat.isInstanceOf[Pat.Var])
    if !hasWildcard then
      sb.append(s"  default: throw new RuntimeException(\"JavacJitBackend: inline match no case matched, tag=\" + inst.typeTag());\n")
    sb.append("}")
    sb.toString

  /** Emit one match arm for inline-accum: `case TAG: { <bindings>; targetVar += body; break; }` */
  private def walkArmForAccum(
    c:           scala.meta.Case,
    ctx:         GenCtx,
    interp:      scalascript.interpreter.Interpreter,
    intTag:      Int,
    accIsDouble: Boolean,
    targetVar:   String
  ): String | Null =
    if c.cond.nonEmpty then return null
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n
          case _            => return null
        val argPats = ext.argClause.values.toArray
        val n = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            // An unreferenced named binding is treated as a wildcard so no field
            // is extracted (extracting an unused ref field as IntV would crash).
            case Pat.Var(Term.Name(bn)) => bindNames(i) = if bindingReferenced(c, bn) then bn else "_unused$" + i
            case _: Pat.Wildcard        => bindNames(i) = "_unused$" + i
            case _                      => return null
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return null
        val bindIsRef = new Array[Boolean](n)
        var bi = 0
        while bi < n do
          if bindNames(bi).startsWith("_unused$") then bindIsRef(bi) = false
          else bindIsRef(bi) = bindingIsRef(c.body, bindNames(bi), ctx)
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
        if intTag > 0 then sb.append(s"  case $intTag: {\n")
        else               sb.append(s"""  case 0: { // untagged fallback\n""")
        val faVar = s"__fa_${sanitize(ctorName)}"
        if n > 0 then sb.append(s"    Object[] $faVar = inst.fieldsArr();\n")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val readExpr = s"$faVar[$fi]"
            if isRef then
              sb.append(s"    Object $jvar = $readExpr;\n")
            else if accIsDouble then
              val raw = s"_rf${fi}_${sanitize(ctorName)}"
              sb.append(s"    Object $raw = $readExpr;\n")
              sb.append(s"    double $jvar = $raw instanceof scalascript.interpreter.DataValue.DoubleV ? ((scalascript.interpreter.DataValue.DoubleV) $raw).v() : (double) ((scalascript.interpreter.DataValue.IntV) $raw).v();\n")
            else
              sb.append(s"    long $jvar = ((scalascript.interpreter.DataValue.IntV) ($readExpr)).v();\n")
          fi += 1
        val armBodyJava = if accIsDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        sb.append(s"    $targetVar += $armBodyJava;\n")
        sb.append(s"    break;\n")
        sb.append(s"  }\n")
        sb.toString
      case _: Pat.Wildcard =>
        val armBodyJava = if accIsDouble then walkDouble(c.body, ctx) else walkLong(c.body, ctx)
        if armBodyJava == null then return null
        s"  default: { $targetVar += $armBodyJava; break; }\n"
      case pv: scala.meta.Pat.Var =>
        val xName = pv.name.value
        val xBind = sanitize(xName) + "_a"
        val newCtx = ctx.withBindings(Map(xName -> (xBind, true)))
        val armBodyJava = if accIsDouble then walkDouble(c.body, newCtx) else walkLong(c.body, newCtx)
        if armBodyJava == null then return null
        s"  default: { Object $xBind = inst; $targetVar += $armBodyJava; break; }\n"
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
            // An unreferenced named binding is treated as a wildcard so no field
            // is extracted (extracting an unused ref field as IntV would crash).
            case Pat.Var(Term.Name(bn)) => bindNames(i) = if bindingReferenced(c, bn) then bn else "_unused$" + i
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
        if n > 0 then sb.append(s"Object[] $faVar = inst.fieldsArr();\n      ")
        var fi = 0
        while fi < n do
          if !bindNames(fi).startsWith("_unused$") then
            val (jvar, isRef) = bindingMap(bindNames(fi))
            val readExpr = s"$faVar[$fi]"
            if isRef then sb.append(s"Object $jvar = $readExpr;\n      ")
            else          sb.append(s"long $jvar = ((scalascript.interpreter.DataValue.IntV)($readExpr)).v();\n      ")
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
    sb.append(s"scalascript.interpreter.Value$$package$$Value$$InstanceV inst = (scalascript.interpreter.Value$$package$$Value$$InstanceV) $scrutJava;\n    ")
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
  /** Direction A.5: Emit Java local declarations for `Defn.Val` stats,
   *  followed by `return <expr>;` for the last `Term`. Ref-valued immutable
   *  locals use `Object`; primitive locals use `long`/`double`. */
  /** Compile one void statement inside a while body: assignment, local `var`/`val`
   *  declaration, nested `while`, or discarded expr. Returns the emitted Java plus
   *  the (possibly extended) context to thread into subsequent body statements — a
   *  `var`/`val` decl introduces a binding the following statements can reference. */
  private def walkStatAsVoid(stat: Stat, ctx: GenCtx): (String, GenCtx) | Null = stat match
    // slice 2: in-place array store `a(idx) = x` on a mutable-array local → arrayUpdateLong.
    // Only ArrayV (the seqLocals `true` flag) supports stores; the helper throws on a bad
    // receiver so a mis-shaped store bails the whole loop to tree-walk.
    case Term.Assign(Term.Apply.After_4_6_0(an: Term.Name, argClause), rhs: Term)
        if argClause.values.lengthCompare(1) == 0 && ctx.seqLocals.get(an.value).contains(true) =>
      val ref = ctx.resolveLocal(an.value)
      if ref == null then return null
      val idx = walkLong(argClause.values.head, ctx)
      if idx == null then return null
      val v = walkLong(rhs, ctx)
      if v == null then return null
      (s"scalascript.interpreter.vm.jit.JitRefDispatch$$.MODULE$$.arrayUpdateLong((Object) ($ref), $idx, $v);\n        ", ctx)
    case Term.Assign(nm: Term.Name, rhs: Term) =>
      val jn = ctx.resolveLocal(nm.value)
      if jn == null then return null
      // Stage 8: ref-typed assignment.
      if ctx.isRefName(nm.value) then
        val r = walkRef(rhs, ctx)
        if r == null then return null
        (s"$jn = $r;\n        ", ctx)
      else
        val e = if ctx.isDouble then walkDouble(rhs, ctx) else walkLong(rhs, ctx)
        if e == null then return null
        (s"$jn = $e;\n        ", ctx)
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs: Term) =>
      val jn = sanitize(n.value)
      // Stage 8: ref-typed var.
      if isRefValRhs(rhs, ctx) then
        val r = walkRef(rhs, ctx)
        if r == null then return null
        (s"Object $jn = $r;\n        ", ctx.withBindings(Seq(n.value -> (jn, true))))
      else
        val e = if ctx.isDouble then walkDouble(rhs, ctx) else walkLong(rhs, ctx)
        if e == null then return null
        val jType = if ctx.isDouble then "double" else "long"
        (s"$jType $jn = $e;\n        ", ctx.withBindings(Seq(n.value -> (jn, false))))
    case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
      val jn = sanitize(n.value)
      if isRefValRhs(rhs, ctx) then
        val r = walkRef(rhs, ctx)
        if r == null then return null
        val nextCtx = seqCtorKind(rhs) match
          case Some(isArray) => ctx.withSeqLocal(n.value, jn, isArray)
          case None          => ctx.withBindings(Seq(n.value -> (jn, true)))
        (s"Object $jn = $r;\n        ", nextCtx)
      else
        val e = if ctx.isDouble then walkDouble(rhs, ctx) else walkLong(rhs, ctx)
        if e == null then return null
        val jType = if ctx.isDouble then "double" else "long"
        (s"$jType $jn = $e;\n        ", ctx.withBindings(Seq(n.value -> (jn, false))))
    case w: Term.While =>
      val ws = walkWhileAsStmt(w, ctx)
      if ws == null then return null
      (s"$ws\n        ", ctx)
    case ti: Term.If =>
      val isVoidElse = ti.elsep match
        case _: Lit.Unit | Term.Block(Nil) => true
        case _                             => false
      if !isVoidElse then return null
      val condStr = walkBool(ti.cond, ctx)
      if condStr == null then return null
      val bodyStats: List[Stat] = ti.thenp match
        case Term.Block(ss) => ss
        case s: Stat        => List(s)
      val sb = new StringBuilder
      sb.append(s"if ($condStr) {\n        ")
      var curCtx = ctx
      var rem = bodyStats
      var ok = true
      while rem.nonEmpty && ok do
        val res = walkStatAsVoid(rem.head, curCtx)
        if res == null then ok = false
        else { sb.append(res._1); curCtx = res._2 }
        rem = rem.tail
      if !ok then return null
      sb.append("}\n        ")
      (sb.toString, ctx)
    case e: Term =>
      val str = if ctx.isDouble then walkDouble(e, ctx) else walkLong(e, ctx)
      if str == null then null else (s"$str;\n        ", ctx)
    case _ => null

  /** Emit a Java while-statement for a `Term.While` appearing as a non-final
   *  statement in a function body. Body stats are compiled as void (no return),
   *  threading bindings so an inner `var` is visible to later stats and to a
   *  nested `while`. Inner decls stay Java-block-scoped to the emitted `{ }`. */
  private def walkWhileAsStmt(w: Term.While, ctx: GenCtx): String | Null =
    val condStr = walkBool(w.expr, ctx)
    if condStr == null then return null
    val bodyStats: List[Stat] = w.body match
      case Term.Block(ss) => ss
      case s: Stat        => List(s)
    val sb = new StringBuilder
    sb.append(s"while ($condStr) {\n        ")
    var curCtx = ctx
    val iter = bodyStats.iterator
    while iter.hasNext do
      val res = walkStatAsVoid(iter.next(), curCtx)
      if res == null then return null
      sb.append(res._1)
      curCtx = res._2
    sb.append("}")
    sb.toString

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
          // Stage 9: `val f = (x: T) => body` — track the lambda for inline at call site.
          case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term.Function) =>
            val ps = rhs.paramClause.values
            val pNames = ps.iterator.map(_.name.value).toArray
            curCtx = curCtx.withLambda(n.value, pNames, rhs.body)
          case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
            val jn = sanitize(n.value)
            if isRefValRhs(rhs, curCtx) then
              val r = walkRef(rhs, curCtx)
              if r == null then return null
              sb.append(s"Object $jn = $r;\n      ")
              curCtx = seqCtorKind(rhs) match
                case Some(isArray) => curCtx.withSeqLocal(n.value, jn, isArray)
                case None          => curCtx.withBindings(Seq(n.value -> (jn, true)))
            else
              val e = if curCtx.isDouble then walkDouble(rhs, curCtx)
                      else walkLong(rhs, curCtx)
              if e == null then return null
              val jType = if curCtx.isDouble then "double" else "long"
              sb.append(s"$jType $jn = $e;\n      ")
              curCtx = curCtx.withBindings(Seq(n.value -> (jn, false)))
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs: Term) =>
            val jn = sanitize(n.value)
            // Stage 8: ref-typed var (e.g. `var m: Map = Map[Int, Int]()`).
            if isRefValRhs(rhs, curCtx) then
              val r = walkRef(rhs, curCtx)
              if r == null then return null
              sb.append(s"Object $jn = $r;\n      ")
              curCtx = curCtx.withBindings(Seq(n.value -> (jn, true)))
            else
              val e = if curCtx.isDouble then walkDouble(rhs, curCtx)
                      else walkLong(rhs, curCtx)
              if e == null then return null
              val jType = if curCtx.isDouble then "double" else "long"
              sb.append(s"$jType $jn = $e;\n      ")
              curCtx = curCtx.withBindings(Seq(n.value -> (jn, false)))
          case Term.Assign(nm: Term.Name, rhs: Term) =>
            val jn = curCtx.resolveLocal(nm.value)
            if jn == null then return null
            // Stage 8: ref-typed assignment (e.g. `m = m.updated(k, v)`).
            if curCtx.isRefName(nm.value) then
              val r = walkRef(rhs, curCtx)
              if r == null then return null
              sb.append(s"$jn = $r;\n      ")
            else
              val e = if curCtx.isDouble then walkDouble(rhs, curCtx) else walkLong(rhs, curCtx)
              if e == null then return null
              sb.append(s"$jn = $e;\n      ")
          case w: Term.While =>
            val ws = walkWhileAsStmt(w, curCtx)
            if ws == null then return null
            sb.append(ws).append("\n      ")
          case ti: Term.If =>
            val isVoidElse = ti.elsep match
              case _: Lit.Unit | Term.Block(Nil) => true
              case _                             => false
            if !isVoidElse then return null
            val condStr = walkBool(ti.cond, curCtx)
            if condStr == null then return null
            val bodyStats: List[Stat] = ti.thenp match
              case Term.Block(ss) => ss
              case s: Stat        => List(s)
            val ifSb = new StringBuilder
            ifSb.append(s"if ($condStr) {\n      ")
            var innerCtx = curCtx
            var ifRem = bodyStats
            var ifOk = true
            while ifRem.nonEmpty && ifOk do
              val res = walkStatAsVoid(ifRem.head, innerCtx)
              if res == null then ifOk = false
              else { ifSb.append(res._1); innerCtx = res._2 }
              ifRem = ifRem.tail
            if !ifOk then return null
            ifSb.append("}\n      ")
            sb.append(ifSb.toString)
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
          if isRefValRhs(rhs, curCtx) then
            val r = walkRef(rhs, curCtx)
            if r == null then return null
            curCtx = seqCtorKind(rhs) match
              case Some(isArray) => curCtx.withSeqLocal(n.value, jn, isArray)
              case None          => curCtx.withBindings(Seq(n.value -> (jn, true)))
          else
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

  private def asSelfRecur(t: Term, funName: String): Option[List[Term]] =
    JitPredicates.asSelfRecur(t, funName)

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

  /** True iff `bindingName` appears as a `Term.Name` arg in a JIT-able call
   *  position whose corresponding callee param is ref-typed. Used by `walkArm`
   *  to classify pattern bindings as `Object` in Java for both self-recursive
   *  and co-emitted sibling calls. */
  // Shared with AsmJitBackend via JitPredicates (callee ref-ness resolved
  // per-backend through GenCtx.callArgIsRef → callParamIsRef).
  private def bindingIsRef(armBody: Term, bindingName: String, ctx: GenCtx): Boolean =
    JitPredicates.bindingIsRef(armBody, bindingName, ctx)

  /** True iff `name` occurs as a `Term.Name` anywhere in the arm's body or guard.
   *  A named pattern binding that is NEVER referenced needs no field extraction —
   *  and extracting it eagerly as an `IntV` (the default when `bindingIsRef` is
   *  false) throws `ClassCastException` when the field is actually a ref (e.g.
   *  `case Bin(l, r) => …` where `l`/`r` are unused `E` values). Callers treat an
   *  unreferenced binding as a wildcard, so no local is emitted. Conservative: any
   *  textual occurrence (even a shadowing inner binder) counts as "used", so we
   *  never drop a binding the body actually reads. */
  private def bindingReferenced(c: scala.meta.Case, name: String): Boolean =
    var hit = false
    def walk(t: scala.meta.Tree): Unit =
      if !hit then t match
        case Term.Name(n) if n == name => hit = true
        case _                         => t.children.foreach(walk)
    walk(c.body)
    c.cond.foreach(walk)
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
    val isCallee:        Boolean = false,
    // Local val-bound InstanceV names (frame locals of the benched fn) so a
    // ref arg like `normSq(v)` JITs even though `v` is not in interp.globals.
    val localRefs:       Set[String] = Set.empty
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
    interp: scalascript.interpreter.Interpreter | Null = null,
    locals: Map[String, Value]
  ): WhileJitEntry | Null =
    if !enabled then return null
    // Global cache: avoid javac on every Interpreter instance / benchmark iter.
    val globalCached = whileLongGlobalCache.get(cond)
    if globalCached eq WhileLongMiss then return null
    if globalCached != null then return globalCached.asInstanceOf[WhileJitEntry]
    val localRefs = locals.iterator.collect { case (k, _: Value.InstanceV) => k }.toSet
    val ctx = new WhileGenCtx(
      names,
      interp,
      scala.collection.mutable.LinkedHashMap.empty[String, String],
      localRefs = localRefs
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
    val jitPkg = "scalascript.interpreter.vm.jit"
    val sb = new StringBuilder
    sb.append(s"public final class $className implements $jitPkg.WhileLongRunFn {\n")
    sb.append(s"  @Override public void run(long[] v) { $className.runStatic(v); }\n")
    sb.append(s"  public static void runStatic(long[] v) {\n")
    // Ref preamble: if any ObjToLong fn calls were emitted, load the fn and
    // ref arrays from TLS once before the loop and extract to locals so the
    // JVM can treat them as de-facto constants after the first iteration.
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
    val task = compiler.getTask(null, fm, null, jitClasspathOptions, null, java.util.Arrays.asList(javaFile))
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
    val runFn: WhileLongRunFn | Null =
      try { val i = cls.getConstructor().newInstance(); if i.isInstanceOf[WhileLongRunFn] then i.asInstanceOf[WhileLongRunFn] else null }
      catch case _: Throwable => null
    if runFn == null then
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
      runFn,
      new Array[Long](names.length),
      if ctx.refNames.isEmpty then Array.empty[String] else ctx.refNames.toArray,
      refFnsArr,
      refObjFnsArr
    )
    whileLongGlobalCache.put(cond, entry.asInstanceOf[AnyRef])
    entry

  // ── Stream.emit while-loop JIT ──────────────────────────────────────────

  private val whileLongEmitGlobalCache: java.util.IdentityHashMap[Term, AnyRef] =
    java.util.IdentityHashMap()
  private val WhileLongEmitMiss: AnyRef = new AnyRef

  override def tryCompileWhileLongEmit(
    cond:        Term,
    emitArgs:    Array[Term],
    allSlots:    Array[String],
    assignNames: Array[String],
    rhs:         Array[Term],
    interp:      scalascript.interpreter.Interpreter | Null
  ): WhileLongEmitRunFn | Null =
    if !enabled then return null
    val cached = whileLongEmitGlobalCache.get(cond)
    if cached eq WhileLongEmitMiss then return null
    if cached != null then return cached.asInstanceOf[WhileLongEmitRunFn]
    // Walk all expressions using allSlots as the slot name map.
    val ctx = new WhileGenCtx(
      allSlots,
      interp,
      scala.collection.mutable.LinkedHashMap.empty[String, String]
    )
    val condJava = walkLocalBoolCtx(cond, ctx)
    if condJava == null then
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    val emitJava = new Array[String](emitArgs.length)
    var ei = 0
    while ei < emitArgs.length do
      emitJava(ei) = walkLocalSlotCtx(emitArgs(ei), ctx)
      if emitJava(ei) == null then
        whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
      ei += 1
    val rhsJava = new Array[String](rhs.length)
    var k = 0
    while k < rhs.length do
      rhsJava(k) = walkLocalSlotCtx(rhs(k), ctx)
      if rhsJava(k) == null then
        whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
      k += 1
    // Emit-while supports only pure-Long slots (no ref fns, no ref globals).
    if ctx.refFnNames.nonEmpty || ctx.refObjFnNames.nonEmpty || ctx.refNames.nonEmpty then
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    val className = s"WhileLongEmit_${Integer.toUnsignedString(System.identityHashCode(cond))}"
    val jitPkg    = "scalascript.interpreter.vm.jit"
    val sb        = new StringBuilder
    sb.append(s"public final class $className implements $jitPkg.WhileLongEmitRunFn {\n")
    sb.append(s"  @Override public int run(long[] v, long[] buf, int bufLen) { return $className.runStatic(v, buf, bufLen); }\n")
    sb.append(s"  public static int runStatic(long[] v, long[] buf, int bufLen) {\n")
    var i = 0
    while i < allSlots.length do
      sb.append(s"    long _v$i = v[$i];\n")
      i += 1
    sb.append(s"    while ($condJava) {\n")
    ei = 0
    while ei < emitJava.length do
      sb.append(s"      buf[bufLen++] = ${emitJava(ei)};\n")
      ei += 1
    i = 0
    while i < assignNames.length do
      // Find slot index of the assign target name.
      var si = 0
      while si < allSlots.length && allSlots(si) != assignNames(i) do si += 1
      sb.append(s"      _v$si = ${rhsJava(i)};\n")
      i += 1
    sb.append("    }\n")
    i = 0
    while i < allSlots.length do
      sb.append(s"    v[$i] = _v$i;\n")
      i += 1
    sb.append("    return bufLen;\n  }\n}\n")
    val source   = sb.toString
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    val classBytes = new ByteArrayOutputStream()
    val javaFile   = new SimpleJavaFileObject(URI.create(s"string:///$className.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = classBytes
    val task = compiler.getTask(null, fm, null, jitClasspathOptions, null, java.util.Arrays.asList(javaFile))
    val ok   = try task.call().booleanValue() catch case _: Throwable => false
    if !ok then
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    val loader = new ClassLoader(classOf[JavacJitBackend.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls = try loader.loadClass(className) catch case _: Throwable =>
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    val runFn: WhileLongEmitRunFn | Null =
      try { val i = cls.getConstructor().newInstance(); if i.isInstanceOf[WhileLongEmitRunFn] then i.asInstanceOf[WhileLongEmitRunFn] else null }
      catch case _: Throwable => null
    if runFn == null then
      whileLongEmitGlobalCache.put(cond, WhileLongEmitMiss); return null
    whileLongEmitGlobalCache.put(cond, runFn.asInstanceOf[AnyRef])
    runFn

  // ── mixed while + foreach fused JIT ─────────────────────────────────────

  private val whileMixedGlobalCache: java.util.IdentityHashMap[Term, AnyRef] =
    java.util.IdentityHashMap()
  private val WhileMixedMiss: AnyRef = new AnyRef

  /** Analyse `xs.foreach(p => { acc = acc + fn(p) })` and extract
   *  `(listName, fnName)` or return null if the pattern doesn't match. */
  private def analyzeForeachApply(foreachApply: Term, accName: String): (String, String) | Null =
    foreachApply match
      case ta: Term.Apply =>
        ta.fun match
          case Term.Select(qual, Term.Name("foreach")) =>
            val listName = qual match
              case Term.Name(n) => n
              case _            => return null
            ta.argClause.values match
              case List(fn: Term.Function) if fn.paramClause.values.lengthCompare(1) == 0 =>
                val paramName = fn.paramClause.values.head.name.value
                if paramName.isEmpty then return null
                // Expect `{ accName = accName + fnName(paramName) }` in body
                val core = fn.body match
                  case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
                    b.stats.head match
                      case t: Term => t
                      case _       => return null
                  case t => t
                core match
                  case a: Term.Assign =>
                    a.lhs match
                      case Term.Name(`accName`) =>
                        a.rhs match
                          case Term.ApplyInfix.After_4_6_0(Term.Name(`accName`), op, _, argClause)
                              if op.value == "+" && argClause.values.lengthCompare(1) == 0 =>
                            argClause.values.head match
                              case ap: Term.Apply
                                  if ap.argClause.values.lengthCompare(1) == 0 =>
                                ap.fun match
                                  case Term.Name(fnName) =>
                                    ap.argClause.values.head match
                                      case Term.Name(`paramName`) => (listName, fnName)
                                      case _                      => null
                                  case _ => null
                              // Identity addend `acc = acc + paramName` — empty
                              // fnName sentinel; the emit unboxes the item directly.
                              case Term.Name(`paramName`) => (listName, "")
                              case _ => null
                          case _ => null
                      case _ => null
                  case _ => null
              case _ => null
          case _ => null
      case _ => null

  /** Analyse `m.foreach((k, v) => { acc = acc + k })` or
   *  `m.foreach((k, v) => { acc = acc + v })` (Map 2-param closure).
   *  Returns `(mapName, useFirst)` where `useFirst=true` means use the key
   *  (first param), `false` means use the value (second param). */
  private def analyzeForeachMapApply(foreachApply: Term, accName: String): (String, Boolean) | Null =
    foreachApply match
      case ta: Term.Apply =>
        ta.fun match
          case Term.Select(qual, Term.Name("foreach")) =>
            val mapName = qual match
              case Term.Name(n) => n
              case _            => return null
            ta.argClause.values match
              case List(fn: Term.Function) if fn.paramClause.values.lengthCompare(2) == 0 =>
                val p1 = fn.paramClause.values.head.name.value
                val p2 = fn.paramClause.values(1).name.value
                if p1.isEmpty || p2.isEmpty then return null
                val core = fn.body match
                  case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
                    b.stats.head match
                      case t: Term => t
                      case _       => return null
                  case t => t
                core match
                  case a: Term.Assign =>
                    a.lhs match
                      case Term.Name(`accName`) =>
                        a.rhs match
                          case Term.ApplyInfix.After_4_6_0(Term.Name(`accName`), op, _, argClause)
                              if op.value == "+" && argClause.values.lengthCompare(1) == 0 =>
                            argClause.values.head match
                              case Term.Name(`p1`) => (mapName, true)
                              case Term.Name(`p2`) => (mapName, false)
                              case _               => null
                          case _ => null
                      case _ => null
                  case _ => null
              case _ => null
          case _ => null
      case _ => null

  override def tryCompileWhileMixed(
    cond:         Term,
    names:        Array[String],
    rhs:          Array[Term],
    foreachApply: Term,
    accName:      String,
    accIsDouble:  Boolean,
    interp:       scalascript.interpreter.Interpreter
  ): WhileJitEntry | Null =
    if !enabled then return null
    val cached = whileMixedGlobalCache.get(foreachApply)
    if cached eq WhileMixedMiss then return null
    if cached != null then return cached.asInstanceOf[WhileJitEntry]

    // Map 2-param foreach path: m.foreach((k, v) => { acc = acc + v })
    val mapInfo = analyzeForeachMapApply(foreachApply, accName)
    if mapInfo != null then
      return tryCompileWhileMapForeach(
        cond, names, rhs, foreachApply, accIsDouble, mapInfo, interp
      )

    val info = analyzeForeachApply(foreachApply, accName)
    if info == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val (listName, fnName) = info

    // Accept val-bound ListV or SetV receivers.
    val listVal = interp.globals.getOrElse(listName, null)
    val receiverIsList = listVal.isInstanceOf[scalascript.interpreter.Value.ListV]
    val receiverIsSet  = !receiverIsList && listVal.isInstanceOf[scalascript.interpreter.Value.SetV]
    if !receiverIsList && !receiverIsSet then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null

    // Identity foreach `xs.foreach(s => acc = acc + s)`: empty fnName sentinel.
    // No inner fn to resolve/JIT — the pre-pass unboxes each item directly.
    val isIdentity = fnName.isEmpty

    // Resolve and JIT-compile the inner function (skipped for identity).
    val fnVal = if isIdentity then null else interp.globals.getOrElse(fnName, null)
    val (fnObjToDouble, fnObjToLong): (ObjToDouble | Null, ObjToLong | Null) =
      if isIdentity then (null, null)
      else
        if !fnVal.isInstanceOf[scalascript.interpreter.Value.FunV] then
          whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
          return null
        val jitR = tryCompile(fnVal.asInstanceOf[scalascript.interpreter.Value.FunV], interp)
        if jitR == null || jitR.direct == null then
          whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
          return null
        val (isDoubleOk, d, l) =
          if accIsDouble then
            jitR.direct match
              case f: ObjToDouble => (true, f, null)
              case _              => (false, null, null)
          else
            jitR.direct match
              case f: ObjToLong => (true, null, f)
              case _            => (false, null, null)
        if !isDoubleOk then
          whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
          return null
        (d, l)

    // Direct-unbox snippet for an identity item expression (Long/Double acc).
    val valuePkgE = "scalascript.interpreter"
    def idUnbox(itemExpr: String): String =
      if accIsDouble then s"(($valuePkgE.DataValue.DoubleV) $itemExpr).v()"
      else                s"(($valuePkgE.DataValue.IntV) $itemExpr).v()"

    // Try to inline the match body — eliminates ObjToLong virtual dispatch.
    val funVTyped =
      if isIdentity then null else fnVal.asInstanceOf[scalascript.interpreter.Value.FunV]
    val inlineMatchSwitch: String | Null =
      if isIdentity then null else tryBuildInlineMatchAccum(funVTyped, accIsDouble, interp)
    // LICM: hoist the pure foreach-accumulator out of the outer while.
    // The foreach result is loop-invariant: coll is val-bound, items are immutable,
    // the match-body (when present) is structurally pure (walkLong/walkDouble reject
    // Term.Assign and impure calls), and the outer while body can only modify slot
    // vars (_v0..) via walkLocalSlotCtx — never globals the fn might read.
    // When inlineMatchSwitch succeeded, use a second invSumMatchSwitch for the
    // pre-pass; otherwise use _fn0/_dfn0 virtual dispatch in the pre-pass.
    val invSumMatchSwitch: String | Null =
      if inlineMatchSwitch != null then
        tryBuildInlineMatchAccum(funVTyped, accIsDouble, interp, targetVar = "_invSum")
      else null

    // Compile cond + int-assign RHSes via existing walkers.
    val ctx = new WhileGenCtx(names, interp, scala.collection.mutable.LinkedHashMap.empty)
    val condJava = walkLocalBoolCtx(cond, ctx)
    if condJava == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val rhsJava = new Array[String](names.length)
    var k = 0
    while k < names.length do
      rhsJava(k) = walkLocalSlotCtx(rhs(k), ctx)
      if rhsJava(k) == null then
        whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
        return null
      k += 1

    // Generate the fused Java class.
    val accSlotIdx = names.length
    val className  = s"WhileMixed_${Integer.toUnsignedString(System.identityHashCode(foreachApply))}"
    val jitPkg     = "scalascript.interpreter.vm.jit"
    val valuePkg   = "scalascript.interpreter"
    val sb = new StringBuilder
    sb.append(s"public final class $className implements $jitPkg.WhileLongRunFn {\n")
    sb.append(s"  @Override public void run(long[] v) { $className.runStatic(v); }\n")
    sb.append(s"  @SuppressWarnings(\"unchecked\")\n")
    sb.append(s"  public static void runStatic(long[] v) {\n")
    // Load function from TLS only when not inlining the match body and not the
    // identity foreach (which has no inner fn).
    if inlineMatchSwitch == null && !isIdentity then
      if accIsDouble then
        sb.append(s"    $jitPkg.ObjToDouble _dfn0 = $jitPkg.JitGlobals.getRefDoubleFns()[0];\n")
      else
        sb.append(s"    $jitPkg.ObjToLong _fn0 = $jitPkg.JitGlobals.getRefFns()[0];\n")
    if receiverIsSet then
      sb.append(s"    $valuePkg.Value$$package$$Value$$SetV _set0 = ($valuePkg.Value$$package$$Value$$SetV) $jitPkg.JitGlobals.getRefs()[0];\n")
    else if inlineMatchSwitch != null then
      // Pre-extracted Object[] — EvalRuntime converts ListV to array before invocation.
      sb.append(s"    Object[] _larr = (Object[]) $jitPkg.JitGlobals.getRefs()[0];\n")
      sb.append(s"    int _llen = _larr.length;\n")
    else
      sb.append(s"    $valuePkg.Value$$package$$Value$$ListV _list0 = ($valuePkg.Value$$package$$Value$$ListV) $jitPkg.JitGlobals.getRefs()[0];\n")
    // Load int slots.
    k = 0
    while k < names.length do
      sb.append(s"    long _v$k = v[$k];\n")
      k += 1
    // Load accumulator.
    if accIsDouble then
      sb.append(s"    double _acc = Double.longBitsToDouble(v[$accSlotIdx]);\n")
    else
      sb.append(s"    long _acc = v[$accSlotIdx];\n")
    // Always LICM: compute _invSum once before the outer while.
    // The pre-pass shape depends on receiver type and whether the match body is inlined.
    if accIsDouble then sb.append(s"    double _invSum = 0.0;\n")
    else               sb.append(s"    long _invSum = 0L;\n")
    if receiverIsSet then
      sb.append(s"    scala.collection.Iterator<?> _invIter = _set0.items().iterator();\n")
      sb.append(s"    while (_invIter.hasNext()) {\n")
      if isIdentity then
        sb.append(s"      _invSum += ${idUnbox("_invIter.next()")};\n")
      else if invSumMatchSwitch != null then
        sb.append(s"      $valuePkg.Value$$package$$Value$$InstanceV inst = ($valuePkg.Value$$package$$Value$$InstanceV) _invIter.next();\n")
        sb.append(s"      $invSumMatchSwitch\n")
      else if accIsDouble then
        sb.append(s"      _invSum += _dfn0.apply(_invIter.next());\n")
      else
        sb.append(s"      _invSum += _fn0.apply(_invIter.next());\n")
      sb.append(s"    }\n")
    else if invSumMatchSwitch != null then
      // List + inline match: pre-extracted array (no virtual dispatch).
      sb.append(s"    for (int _li = 0; _li < _llen; _li++) {\n")
      sb.append(s"      $valuePkg.Value$$package$$Value$$InstanceV inst = ($valuePkg.Value$$package$$Value$$InstanceV) _larr[_li];\n")
      sb.append(s"      $invSumMatchSwitch\n")
      sb.append(s"    }\n")
    else
      // List + fn (or identity): head/tail traversal (no pre-extraction needed).
      sb.append(s"    scala.collection.immutable.List<?> _invItems = _list0.items();\n")
      sb.append(s"    while (!_invItems.isEmpty()) {\n")
      if isIdentity then
        sb.append(s"      _invSum += ${idUnbox("_invItems.head()")};\n")
      else if accIsDouble then
        sb.append(s"      _invSum += _dfn0.apply(_invItems.head());\n")
      else
        sb.append(s"      _invSum += _fn0.apply(_invItems.head());\n")
      sb.append(s"      _invItems = (scala.collection.immutable.List<?>) _invItems.tail();\n")
      sb.append(s"    }\n")
    // Outer while: tight loop — accumulate constant _invSum per iteration.
    sb.append(s"    while ($condJava) {\n")
    sb.append(s"      _acc += _invSum;\n")
    k = 0
    while k < names.length do
      sb.append(s"      _v$k = ${rhsJava(k)};\n")
      k += 1
    sb.append(s"    }\n")
    // Write back slots.
    k = 0
    while k < names.length do
      sb.append(s"    v[$k] = _v$k;\n")
      k += 1
    if accIsDouble then
      sb.append(s"    v[$accSlotIdx] = Double.doubleToRawLongBits(_acc);\n")
    else
      sb.append(s"    v[$accSlotIdx] = _acc;\n")
    sb.append(s"  }\n")
    // Co-emit any pure-fn callees referenced in rhs/cond.
    val emissionIt = ctx.pureFnEmissions.valuesIterator
    while emissionIt.hasNext do sb.append(emissionIt.next())
    sb.append("}\n")

    val source = sb.toString
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val classBytes = new ByteArrayOutputStream()
    val javaFile = new SimpleJavaFileObject(URI.create(s"string:///$className.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = classBytes
    val task = compiler.getTask(null, fm, null, jitClasspathOptions, null, java.util.Arrays.asList(javaFile))
    val ok = try task.call().booleanValue() catch case _: Throwable => false
    if !ok then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val loader = new ClassLoader(classOf[JavacJitBackend.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls = try loader.loadClass(className) catch case _: Throwable =>
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val runFn: WhileLongRunFn | Null =
      try { val i = cls.getConstructor().newInstance(); if i.isInstanceOf[WhileLongRunFn] then i.asInstanceOf[WhileLongRunFn] else null }
      catch case _: Throwable => null
    if runFn == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(
      runFn,
      new Array[Long](names.length + 1),
      Array.empty[String],
      if inlineMatchSwitch != null then Array.empty[ObjToLong]
      else if fnObjToLong  != null then Array(fnObjToLong)
      else Array.empty[ObjToLong],
      Array.empty[ObjToObject],
      if inlineMatchSwitch != null then Array.empty[ObjToDouble]
      else if fnObjToDouble != null then Array(fnObjToDouble)
      else Array.empty[ObjToDouble],
      listPreExtract = inlineMatchSwitch != null && receiverIsList
    )
    whileMixedGlobalCache.put(foreachApply, entry.asInstanceOf[AnyRef])
    entry

  /** Compile the `while + m.foreach((k,v) => acc += v)` shape for a `MapV`
   *  receiver.  Emits a Java iterator loop over `MapV.entries()` that
   *  extracts `IntV.v()` (or `DoubleV.v()`) from each entry directly, without
   *  a JIT-compiled inner function — the closure body is just a direct map
   *  field select, not an arbitrary function call. */
  private def tryCompileWhileMapForeach(
    cond:         Term,
    names:        Array[String],
    rhs:          Array[Term],
    foreachApply: Term,
    accIsDouble:  Boolean,
    mapInfo:      (String, Boolean),
    interp:       scalascript.interpreter.Interpreter
  ): WhileJitEntry | Null =
    val (mapName, useFirst) = mapInfo
    val mapVal = interp.globals.getOrElse(mapName, null)
    if !mapVal.isInstanceOf[scalascript.interpreter.Value.MapV] then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null

    val ctx = new WhileGenCtx(names, interp, scala.collection.mutable.LinkedHashMap.empty)
    val condJava = walkLocalBoolCtx(cond, ctx)
    if condJava == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val rhsJava = new Array[String](names.length)
    var k = 0
    while k < names.length do
      rhsJava(k) = walkLocalSlotCtx(rhs(k), ctx)
      if rhsJava(k) == null then
        whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
        return null
      k += 1

    val accSlotIdx = names.length
    val className  = s"WhileMap_${Integer.toUnsignedString(System.identityHashCode(foreachApply))}"
    val jitPkg     = "scalascript.interpreter.vm.jit"
    val valuePkg   = "scalascript.interpreter"
    val sb = new StringBuilder
    sb.append(s"public final class $className implements $jitPkg.WhileLongRunFn {\n")
    sb.append(s"  @Override public void run(long[] v) { $className.runStatic(v); }\n")
    sb.append(s"  @SuppressWarnings(\"unchecked\")\n")
    sb.append(s"  public static void runStatic(long[] v) {\n")
    // refs[0] is a pre-extracted Object[] of keys or values (no per-iteration iterator allocation).
    sb.append(s"    Object[] _mvals = (Object[]) $jitPkg.JitGlobals.getRefs()[0];\n")
    sb.append(s"    int _mlen = _mvals.length;\n")
    k = 0
    while k < names.length do
      sb.append(s"    long _v$k = v[$k];\n")
      k += 1
    if accIsDouble then
      sb.append(s"    double _acc = Double.longBitsToDouble(v[$accSlotIdx]);\n")
    else
      sb.append(s"    long _acc = v[$accSlotIdx];\n")
    // LICM: map items are val-bound and immutable — hoist the foreach sum.
    if accIsDouble then sb.append(s"    double _invSum = 0.0;\n")
    else               sb.append(s"    long _invSum = 0L;\n")
    sb.append(s"    for (int _mi = 0; _mi < _mlen; _mi++) {\n")
    if accIsDouble then
      sb.append(s"      Object _mval = _mvals[_mi];\n")
      sb.append(s"      _invSum += _mval instanceof $valuePkg.DataValue.DoubleV ? (($valuePkg.DataValue.DoubleV)_mval).v() : (double)(($valuePkg.DataValue.IntV)_mval).v();\n")
    else
      sb.append(s"      _invSum += (($valuePkg.DataValue.IntV)_mvals[_mi]).v();\n")
    sb.append(s"    }\n")
    sb.append(s"    while ($condJava) {\n")
    sb.append(s"      _acc += _invSum;\n")
    k = 0
    while k < names.length do
      sb.append(s"      _v$k = ${rhsJava(k)};\n")
      k += 1
    sb.append(s"    }\n")
    k = 0
    while k < names.length do
      sb.append(s"    v[$k] = _v$k;\n")
      k += 1
    if accIsDouble then
      sb.append(s"    v[$accSlotIdx] = Double.doubleToRawLongBits(_acc);\n")
    else
      sb.append(s"    v[$accSlotIdx] = _acc;\n")
    sb.append(s"  }\n")
    val emissionIt = ctx.pureFnEmissions.valuesIterator
    while emissionIt.hasNext do sb.append(emissionIt.next())
    sb.append("}\n")

    val source = sb.toString
    val compiler = ToolProvider.getSystemJavaCompiler
    if compiler == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val classBytes = new ByteArrayOutputStream()
    val javaFile = new SimpleJavaFileObject(URI.create(s"string:///$className.java"), JavaFileObject.Kind.SOURCE):
      override def getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    val standard = compiler.getStandardFileManager(null, null, null)
    val fm = new ForwardingJavaFileManager[JavaFileManager](standard):
      override def getJavaFileForOutput(loc: JavaFileManager.Location, name: String, kind: JavaFileObject.Kind, sib: FileObject) =
        new SimpleJavaFileObject(URI.create(s"mem:///$name.class"), kind):
          override def openOutputStream(): OutputStream = classBytes
    val task = compiler.getTask(null, fm, null, jitClasspathOptions, null, java.util.Arrays.asList(javaFile))
    val ok = try task.call().booleanValue() catch case _: Throwable => false
    if !ok then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val loader = new ClassLoader(classOf[JavacJitBackend.type].getClassLoader):
      override def findClass(name: String): Class[?] =
        if name == className then
          val bytes = classBytes.toByteArray
          defineClass(name, bytes, 0, bytes.length)
        else super.findClass(name)
    val cls = try loader.loadClass(className) catch case _: Throwable =>
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null
    val runFn: WhileLongRunFn | Null =
      try { val i = cls.getConstructor().newInstance(); if i.isInstanceOf[WhileLongRunFn] then i.asInstanceOf[WhileLongRunFn] else null }
      catch case _: Throwable => null
    if runFn == null then
      whileMixedGlobalCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(runFn, new Array[Long](names.length + 1),
                                   Array.empty[String], Array.empty[ObjToLong], Array.empty[ObjToObject],
                                   Array.empty[ObjToDouble], mapIsKeyMode = useFirst)
    whileMixedGlobalCache.put(foreachApply, entry.asInstanceOf[AnyRef])
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
              // Compile-time constant fold: if the entire ref-arg chain is val-bound,
              // evaluate it at JIT-compile time and inline the result as a long literal.
              // Only safe for val bindings (immutable), so the cached class stays correct.
              val constVal = evalRefArgConst(args.head, ctx)
              if constVal != null then
                val constResult = jitR.direct.asInstanceOf[ObjToLong].apply(constVal)
                return s"${constResult}L"
              fn.body match
                case tm: Term.Match =>
                  val key = "fn_long_" + pureFnMethodName(fnName.value).stripPrefix("fn_")
                  if !ctx.pureFnEmissions.contains(key) then
                    val pname = fn.params.head
                    val genCtx = new GenCtx(
                      key, Set(pname), Array(pname), Array(true),
                      false, Map.empty, ctx.interp, new CoEmitState
                    )
                    val matchBody = walkMatchBody(tm, genCtx, ctx.interp)
                    if matchBody != null then
                      ctx.pureFnEmissions(key) =
                        s"  public static long $key(Object ${sanitize(pname)}) {\n    $matchBody\n  }\n"
                  if ctx.pureFnEmissions.contains(key) then
                    return s"$key($argRefJava)"
                case _ =>
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
            // RESERVE the method name BEFORE walking the body so a SELF-recursive
            // call inside it (`fib(n-1)` in `def fib(n) = … fib(n-1) + fib(n-2)`)
            // takes the `contains` branch above and emits a self-call `fib(_v0)`
            // instead of re-co-emitting the body → unbounded `walkLocalSlotCtx`
            // recursion → StackOverflowError. This also lets a self-recursive callee
            // co-compile as a real recursive Java static method (JIT, not bail). The
            // placeholder is overwritten with the real source on success and removed
            // on a bail so a later shape can retry cleanly.
            ctx.pureFnEmissions(sanitised) = ""
            val bodyJava = walkLocalSlotCtx(fn.body, calleeCtx)
            if bodyJava == null then
              ctx.pureFnEmissions.remove(sanitised)
              return null
            val params =
              if nargs == 1 then "long _v0"
              else                "long _v0, long _v1"
            val methodSrc =
              s"  public static long $sanitised($params) {\n    return $bodyJava;\n  }\n"
            ctx.pureFnEmissions(sanitised) = methodSrc
            if nargs == 1 then s"$sanitised($a0)"
            else                s"$sanitised($a0, $a1)"
        case _ => null
    // Inline match on a val-bound InstanceV global: `p match { case Pair(a,b) => a+b }`.
    // The scrutinee resolves to a `_rN` ref slot; the match is co-emitted as a static
    // helper method `fn_imatch_HASH(Object scrutName)` that wraps `walkMatchBody` output.
    // The call site emits `fn_imatch_HASH(_rN)`.  Not valid inside callee static methods
    // (isCallee=true) since they have no ref preamble and cannot access TLS ref arrays.
    case tm: Term.Match if ctx.interp != null && !ctx.isCallee =>
      val scrutRefJava = walkRefArgCtx(tm.expr, ctx)
      if scrutRefJava == null then return null
      val scrutName = tm.expr match
        case n: Term.Name => n.value
        case _            => return null
      val methodName = "fn_imatch_" + Integer.toUnsignedString(System.identityHashCode(tm))
      if ctx.pureFnEmissions.contains(methodName) then
        return s"$methodName($scrutRefJava)"
      // Temporary GenCtx: treats the scrutinee as a single ref param so that
      // walkMatchBody finds it in ctx.params and generates the InstanceV cast.
      val genCtx = new GenCtx(
        methodName, Set(scrutName), Array(scrutName), Array(true),
        false, Map.empty, ctx.interp, new CoEmitState
      )
      val matchBody = walkMatchBody(tm, genCtx, ctx.interp)
      if matchBody == null then return null
      val methodSrc =
        s"  public static long $methodName(Object ${sanitize(scrutName)}) {\n    $matchBody\n  }\n"
      ctx.pureFnEmissions(methodName) = methodSrc
      s"$methodName($scrutRefJava)"
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
        case _ if ctx.localRefs.contains(tn.value) =>
          val ri = ctx.refIdx(tn.value)
          s"_r$ri"
        case _ => null
    case ts: Term.Select if ctx.interp != null =>
      ts.qual match
        case qn: Term.Name =>
          ctx.interp.globals.getOrElse(qn.value, null) match
            case inst: Value.InstanceV =>
              val fieldName = ts.name.value
              val fieldVal: Value | Null =
                val arr = inst.fieldsArr
                if arr != null then
                  val fo = ctx.interp.typeFieldOrder.getOrElse(inst.typeName, Nil)
                  val idx = fo.indexOf(fieldName)
                  if idx >= 0 && idx < arr.length then arr(idx) else null
                else inst.fields.getOrElse(fieldName, null)
              fieldVal match
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
          fn.body match
            case tm: Term.Match =>
              val key = "fn_obj_" + pureFnMethodName(fnName.value).stripPrefix("fn_")
              if !ctx.pureFnEmissions.contains(key) then
                val pname = fn.params.head
                val genCtx = new GenCtx(
                  key, Set(pname), Array(pname), Array(true),
                  false, Map.empty, ctx.interp, new CoEmitState
                )
                val refBody = walkRefMatchBody(tm, genCtx, ctx.interp)
                if refBody != null then
                  ctx.pureFnEmissions(key) =
                    s"  public static Object $key(Object ${sanitize(pname)}) {\n    $refBody\n  }\n"
              if ctx.pureFnEmissions.contains(key) then
                return s"$key($innerRefJava)"
            case _ =>
          val oi = ctx.refObjFnIdx(fnName.value)
          s"_objFn$oi.apply($innerRefJava)"
        case _ => null
    case _ => null

  /** Walk a ref-arg term to a concrete Value.InstanceV at JIT-compile time by
   *  following val-bound globals and applying ObjToObject functions eagerly.
   *  Returns non-null only when the entire chain is val-bound (safe to constant-fold). */
  private def evalRefArgConst(t: Term, ctx: WhileGenCtx): AnyRef | Null =
    if ctx.interp == null then return null
    t match
      case tn: Term.Name =>
        if !ctx.interp.valNames.contains(tn.value) then return null
        ctx.interp.globals.getOrElse(tn.value, null) match
          case iv: Value.InstanceV => iv
          case _                   => null
      case ap: Term.Apply =>
        ap.fun match
          case fnName: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
            val innerVal = evalRefArgConst(ap.argClause.values.head, ctx)
            if innerVal == null then return null
            val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
            if !fnV.isInstanceOf[Value.FunV] then return null
            val fn = fnV.asInstanceOf[Value.FunV]
            if fn.params.length != 1 then return null
            val jitR = tryCompile(fn, ctx.interp)
            if jitR == null || !jitR.paramIsRef(0) || jitR.resultIsDouble ||
               jitR.direct == null || !jitR.direct.isInstanceOf[ObjToObject]
            then return null
            jitR.direct.asInstanceOf[ObjToObject].apply(innerVal)
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
  private val javaKeywords: Set[String] = Set(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "_"
  )

  private def sanitize(name: String): String =
    val sb = new StringBuilder(name.length)
    var i = 0
    while i < name.length do
      val c = name.charAt(i)
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
         (c >= '0' && c <= '9') || c == '_' then sb.append(c)
      else sb.append('_')
      i += 1
    if sb.isEmpty || (sb.charAt(0) >= '0' && sb.charAt(0) <= '9') then sb.insert(0, "fn_")
    val s = sb.toString
    if javaKeywords.contains(s) then s + "_" else s
