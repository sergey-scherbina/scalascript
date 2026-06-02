package scalascript.interpreter.vm

import java.lang.invoke.{MethodHandles, MethodHandle, MethodType}
import javax.tools.{ToolProvider, JavaFileObject, SimpleJavaFileObject, ForwardingJavaFileManager, JavaFileManager, FileObject}
import java.io.{ByteArrayOutputStream, OutputStream}
import java.net.URI

import scala.meta.{Term, Lit}
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

  /** Cache by FunV body AST identity. Value is a real `MethodHandle` on hit
   *  or the `BailSentinel` on miss (so we don't re-attempt compilation for the
   *  same body). Synchronized via the cache monitor. */
  private val cache = new java.util.IdentityHashMap[scala.meta.Term, AnyRef]()
  private val BailSentinel: AnyRef = new AnyRef

  def tryCompile(f: Value.FunV): MethodHandle | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length != 1 && f.params.length != 2 then return null
    val body = f.body
    cache.synchronized {
      val cached = cache.get(body)
      if cached != null then
        return if cached eq BailSentinel then null else cached.asInstanceOf[MethodHandle]
    }
    val mh = doCompile(f)
    cache.synchronized {
      cache.put(body, if mh == null then BailSentinel else mh.asInstanceOf[AnyRef])
    }
    mh

  private def doCompile(f: Value.FunV): MethodHandle | Null =
    val paramSet = f.params.toSet
    val ctx = new GenCtx(f.name, paramSet)
    val bodyJava = walkLong(f.body.asInstanceOf[Term], ctx)
    if bodyJava == null then return null

    val className = s"GenJit_${sanitize(f.name)}_${System.identityHashCode(f.body)}"
    val params = f.params.map(p => s"long ${sanitize(p)}").mkString(", ")
    val source =
      s"""public class $className {
         |  public static long ${sanitize(f.name)}($params) {
         |    return $bodyJava;
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

    val mt =
      if f.params.length == 1 then MethodType.methodType(classOf[Long], classOf[Long])
      else MethodType.methodType(classOf[Long], classOf[Long], classOf[Long])
    try MethodHandles.lookup().findStatic(cls, sanitize(f.name), mt)
    catch case _: Throwable => null

  // ── AST → Java source walker (long-typed expressions) ──────────────────────

  private final class GenCtx(val funName: String, val params: Set[String])

  private def walkLong(t: Term, ctx: GenCtx): String | Null = t match
    case Lit.Int(v)  => s"${v}L"
    case Lit.Long(v) => s"${v}L"
    case tn: Term.Name =>
      if ctx.params.contains(tn.value) then sanitize(tn.value)
      else null   // free name (non-param) — bail to tree-walk
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
          // Comparison result projected to a Long (1 or 0) so it composes with
          // arithmetic. Term.If wraps `cond != 0L`-style checks via walkBool.
          val l = walkLong(lhs, ctx); if l == null then return null
          val r = walkLong(argClause.values.head, ctx); if r == null then return null
          s"(($l $opStr $r) ? 1L : 0L)"
        case _ => null
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == ctx.funName =>
          val args = ap.argClause.values
          if args.length != ctx.params.size then return null
          val argStrs = new scala.collection.mutable.ArrayBuffer[String](args.length)
          var rem = args
          while rem.nonEmpty do
            val a = walkLong(rem.head, ctx)
            if a == null then return null
            argStrs += a
            rem = rem.tail
          s"${sanitize(ctx.funName)}(${argStrs.mkString(", ")})"
        case _ => null  // non-self call — bail
    case _ => null

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
