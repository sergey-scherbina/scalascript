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
 *  Gated via env `SSC_JIT_BYTECODE=on` (or `-Dssc.jit.bytecode=on`); default
 *  OFF until benched as a clean win. */
object BytecodeJit:

  val enabled: Boolean =
    sys.env.get("SSC_JIT_BYTECODE").map(_.toLowerCase).contains("on") ||
      sys.props.get("ssc.jit.bytecode").map(_.toLowerCase).contains("on")

  /** Compilation result. `paramIsRef(i)` is true when the i-th param is
   *  passed as an `Object` (an `InstanceV`) — used to drive `JitRuntime`'s
   *  per-arg marshaling (numeric → `long`, ref → `Object`). */
  final class Result(val mh: MethodHandle, val paramIsRef: Array[Boolean])

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

  private def doCompile(f: Value.FunV, interp: scalascript.interpreter.Interpreter): Result | Null =
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
    val ctx = new GenCtx(f.name, paramSet, f.params.toArray, paramIsRef, Map.empty)
    // For a Match-body fn the method body is a series of `if` statements ending
    // in a throw; for an int-arith body it's a single `return <expr>;`. Either
    // way `bodyStmts` carries the FULL Java statement sequence.
    val bodyStmts =
      f.body match
        case tm: Term.Match if paramIsRef.exists(identity) =>
          walkMatchBody(tm, ctx, interp)
        case other =>
          val e = walkLong(other.asInstanceOf[Term], ctx)
          if e == null then null else s"return $e;"
    if bodyStmts == null then return null

    val className = s"GenJit_${sanitize(f.name)}_${System.identityHashCode(f.body)}"
    val params = f.params.zipWithIndex.map { case (p, i) =>
      if paramIsRef(i) then s"Object ${sanitize(p)}"
      else s"long ${sanitize(p)}"
    }.mkString(", ")
    val source =
      s"""public class $className {
         |  public static long ${sanitize(f.name)}($params) {
         |    $bodyStmts
         |  }
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

    val ptypes: Array[Class[?]] = paramIsRef.map(r => if r then classOf[Object] else classOf[Long])
    val mt = MethodType.methodType(classOf[Long], ptypes.asInstanceOf[Array[Class[?]]])
    val mh =
      try MethodHandles.lookup().findStatic(cls, sanitize(f.name), mt)
      catch case _: Throwable => return null
    new Result(mh, paramIsRef)

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
    val bindings:    Map[String, (String, Boolean)]
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
      new GenCtx(funName, params, paramNames, paramIsRef, bindings ++ more)

  private def walkLong(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Int(v)  => s"${v}L"
    case Lit.Long(v) => s"${v}L"
    case tn: Term.Name =>
      // Only int-typed names can be read into a Long expression. Ref-typed
      // names (param scrutinee, ref-classified bindings) cannot.
      if ctx.isRefName(tn.value) then null
      else ctx.resolveLocal(tn.value)
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
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
        case "&&" | "||" =>
          val l = walkBool(lhs, ctx); if l == null then return null
          val r = walkBool(argClause.values.head, ctx); if r == null then return null
          s"($l $opStr $r)"
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
