package scalascript.interpreter.vm.jit

import org.objectweb.asm.{ClassWriter, MethodVisitor, Label}
import org.objectweb.asm.Opcodes.*

import scala.meta.{Term, Lit, Pat, Stat, Defn}
import scala.collection.mutable
import scalascript.interpreter.{Interpreter, Value}
import scalascript.interpreter.vm.JitMissStats

/** Phase C bytecode JIT — ASM backend.
 *
 *  Direct AST → JVM bytecode; no Java source intermediate, no javac startup.
 *  Select via `SSC_JIT_BACKEND=asm`. Default backend remains `JavacJitBackend`. */
object AsmJitBackend extends JitBackend:

  override val id: String = "asm"

  override val enabled: Boolean =
    !sys.env.get("SSC_JIT_BYTECODE").map(_.toLowerCase).contains("off") &&
      !sys.props.get("ssc.jit.bytecode").map(_.toLowerCase).contains("off")

  private val cache        = new java.util.IdentityHashMap[scala.meta.Term, AnyRef]()
  private val BailSentinel = new AnyRef
  private val classCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  // JVM internal names for interpreter types.
  private val instVInt   = "scalascript/interpreter/Value$InstanceV"
  private val tupleVInt  = "scalascript/interpreter/Value$TupleV"
  private val scalaList  = "scala/collection/immutable/List"
  private val intVInt    = "scalascript/interpreter/Value$IntV"
  private val dblVInt    = "scalascript/interpreter/Value$DoubleV"
  private val boolVInt   = "scalascript/interpreter/Value$BoolV"
  private val stringVInt = "scalascript/interpreter/Value$StringV"
  private val optionVInt = "scalascript/interpreter/Value$OptionV"
  private val valueInt   = "scalascript/interpreter/Value"
  private val globalsInt = "scalascript/interpreter/vm/jit/JitGlobals$"
  private val refDispatchInt = "scalascript/interpreter/vm/jit/JitRefDispatch$"
  private val hofDispatchInt = "scalascript/interpreter/vm/jit/JitHofDispatch$"
  private val jitPkg     = "scalascript.interpreter.vm.jit"

  // ── SPI surface ───────────────────────────────────────────────────────────

  override def tryCompile(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length > 3 then return null
    val body = f.body
    cache.synchronized {
      val hit = cache.get(body)
      if hit != null then
        return if hit eq BailSentinel then null else hit.asInstanceOf[JitResult]
    }
    val result = doCompile(f, interp)
    cache.synchronized {
      cache.put(body, if result == null then BailSentinel else result.asInstanceOf[AnyRef])
    }
    if result == null then
      val reasons = classifyBailReasons(f)
      val reason  = if reasons.nonEmpty then
        if reasons.lengthCompare(1) == 0 then reasons.head else JitBailReason.Compound(reasons)
      else JitBailReason.UnknownShape
      JitMissStats.record("asm", reason)
    result

  // ── Shared guards (mirrors JavacJitBackend) ───────────────────────────────

  private def bodyHasDoubleLit(t: Term): Boolean =
    var hit = false
    def walk(tree: scala.meta.Tree): Unit =
      if !hit then tree match
        case _: Lit.Double => hit = true
        case _             => tree.children.foreach(walk)
    walk(t); hit

  private def isBoolReturning(t: Term): Boolean = JitPredicates.isBoolReturning(t)

  private def determineInterface(n: Int, paramIsRef: Array[Boolean], isDouble: Boolean): String | Null =
    if n == 0 then
      if isDouble then s"$jitPkg.DoubleFn0" else s"$jitPkg.LongFn0"
    else if n == 1 then
      if paramIsRef(0) then if isDouble then s"$jitPkg.ObjToDouble" else s"$jitPkg.ObjToLong"
      else if isDouble then s"$jitPkg.DoubleFn1" else s"$jitPkg.LongFn1"
    else if n == 2 then
      (paramIsRef(0), paramIsRef(1)) match
        case (false, false) =>
          if isDouble then s"$jitPkg.DoubleFn2"      else s"$jitPkg.LongFn2"
        case (false, true)  =>
          if isDouble then s"$jitPkg.LongObjToDouble" else s"$jitPkg.LongObjToLong"
        case (true,  false) =>
          if isDouble then s"$jitPkg.ObjLongToDouble" else s"$jitPkg.ObjLongToLong"
        case (true,  true)  =>
          if isDouble then s"$jitPkg.ObjObjToDouble" else s"$jitPkg.ObjObjToLong"
    else if n == 3 then
      (paramIsRef(0), paramIsRef(1), paramIsRef(2)) match
        case (false, false, false) => if isDouble then s"$jitPkg.DoubleFn3"           else s"$jitPkg.LongFn3"
        case (true,  false, false) => if isDouble then s"$jitPkg.ObjLongLongToDouble" else s"$jitPkg.ObjLongLongToLong"
        case (false, true,  false) => if isDouble then s"$jitPkg.LongObjLongToDouble" else s"$jitPkg.LongObjLongToLong"
        case (false, false, true)  => if isDouble then s"$jitPkg.LongLongObjToDouble" else s"$jitPkg.LongLongObjToLong"
        case (true,  true,  false) => if isDouble then s"$jitPkg.ObjObjLongToDouble"  else s"$jitPkg.ObjObjLongToLong"
        case (true,  false, true)  => if isDouble then s"$jitPkg.ObjLongObjToDouble"  else s"$jitPkg.ObjLongObjToLong"
        case (false, true,  true)  => if isDouble then s"$jitPkg.LongObjObjToDouble"  else s"$jitPkg.LongObjObjToLong"
        case (true,  true,  true)  => if isDouble then s"$jitPkg.ObjObjObjToDouble"   else s"$jitPkg.ObjObjObjToLong"
    else null

  private final case class MethodSig(
    methodName: String,
    paramNames: Array[String],
    paramIsRef: Array[Boolean],
    isDouble:   Boolean
  )

  private final class CoEmitState:
    val signatures:   mutable.HashMap[String, MethodSig] =
      mutable.HashMap.empty
    val extraMethods: mutable.LinkedHashMap[String, ClassWriter => Boolean] =
      mutable.LinkedHashMap.empty
    val emitting:     mutable.HashSet[String] =
      mutable.HashSet.empty
    val emitted:      mutable.HashSet[String] =
      mutable.HashSet.empty
    // Siblings co-emitted as `static String f(...)` (String-returning).
    val stringReturning: mutable.HashSet[String] =
      mutable.HashSet.empty
    // Siblings co-emitted as `static Object f(...)` (ref-returning).
    val refReturning: mutable.HashSet[String] =
      mutable.HashSet.empty

  private def classifyParamRefs(f: Value.FunV): Array[Boolean] =
    val paramIsRef = new Array[Boolean](f.params.length)
    // Function-typed params (FunV HOF callbacks) are always refs.
    f.paramTypes.zipWithIndex.foreach { case (pt, i) =>
      if pt.contains("=>") then paramIsRef(i) = true
    }
    def markRef(t: scala.meta.Tree): Unit = t match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name =>
            val idx = f.params.indexOf(n.value)
            // Literal-int matches keep the scrutinee as Long; only ADT matches need ref.
            if idx >= 0 && !isLiteralIntMatch(tm) then paramIsRef(idx) = true
          case _ =>
        markRef(tm.expr)
        tm.casesBlock.cases.foreach(c => markRef(c.body))
      // `param.field` access ⇒ param is an InstanceV ref, not a Long.
      case Term.Select(n: Term.Name, _) =>
        val idx = f.params.indexOf(n.value)
        if idx >= 0 then paramIsRef(idx) = true
      case _ => t.children.foreach(markRef)
    markRef(f.body)
    paramIsRef

  private def jitCompatibleSibling(f: Value.FunV): Boolean =
    f.name.nonEmpty &&
      (f.params.length >= 1 && f.params.length <= 3) &&
      f.usingParams.isEmpty &&
      !f.returnsThrows &&
      (f.defaults.isEmpty || f.defaults.forall(_.isEmpty)) &&
      (f.paramTypes.isEmpty || !f.paramTypes.exists(_.endsWith("*")))

  private def staticMethodName(name: String): String = sanitize(name)

  // ── doCompile ─────────────────────────────────────────────────────────────

  private def doCompile(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if f.usingParams.nonEmpty || f.returnsThrows then return null
    // Bool-body functions compile with a 0/1 wrapper (see emitFnBody fallback).
    // isBoolReturning is still checked in jitCompatibleSibling.
    val paramSet   = f.params.toSet
    val paramIsRef = classifyParamRefs(f)
    val isDouble   = bodyHasDoubleLit(f.body)
    val ifaceName  = determineInterface(f.params.length, paramIsRef, isDouble)
    val coEmit     = new CoEmitState

    // Assign JVM local-variable slots. Object refs use 1 slot; Long/Double use 2.
    val paramSlots = new Array[Int](f.params.length)
    var nextSlot0  = 0
    for i <- 0 until f.params.length do
      paramSlots(i) = nextSlot0; nextSlot0 += (if paramIsRef(i) then 1 else 2)
    val firstLocal = nextSlot0
    var nextLocal  = firstLocal

    val n     = classCounter.incrementAndGet()
    val cname = s"scalascript/interpreter/vm/jit/asm/AsmJit$$$n"

    val staticName = staticMethodName(f.name)
    coEmit.signatures.put(f.name, MethodSig(staticName, f.params.toArray, paramIsRef, isDouble))
    coEmit.emitted.add(f.name)
    val ctx = GenCtx(
      funName          = f.name,
      staticMethodName = staticName,
      params           = paramSet,
      paramNames       = f.params.toArray,
      paramSlots       = paramSlots,
      paramIsRef       = paramIsRef,
      isDouble         = isDouble,
      bindings         = Map.empty,
      interp           = interp,
      coEmit           = coEmit,
      selfClass        = cname,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s },
      paramTypes       = f.paramTypes.toArray
    )

    if f.params.length == 1 && paramIsRef(0) then
      f.body match
        case tm: Term.Match =>
          val refR = tryCompileObjToObject(tm, ctx, interp, cname)
          if refR != null then return refR
        case _ =>

    // LongToObject: recursive ADT-builder `def build(d: Int): Expr = if … then
    // Num(1) else Add(build(d-1), …)`.  Long param, ref (InstanceV) return.
    if f.params.length == 1 && !paramIsRef(0) && !isDouble then
      val objR = tryCompileLongToObject(f, ctx, cname)
      if objR != null then return objR

    val ifaceInternal = if ifaceName != null then ifaceName.replace('.', '/') else null
    val ifaces: Array[String] = if ifaceInternal != null then Array(ifaceInternal) else Array.empty
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", ifaces)

    val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(ALOAD, 0)
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(RETURN); init.visitMaxs(1, 1); init.visitEnd()

    val staticDesc = buildStaticDesc(paramIsRef, isDouble)
    val smv        = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, staticName, staticDesc, null, null)
    smv.visitCode()

    val bodyOk = emitFnBody(f, ctx, interp, smv, isDouble)
    if !bodyOk then return null
    try smv.visitMaxs(0, 0) catch case _: Throwable => return null
    smv.visitEnd()

    val extraIt = coEmit.extraMethods.valuesIterator
    while extraIt.hasNext do
      if !extraIt.next()(cw) then return null

    if ifaceName != null then
      val bMv = cw.visitMethod(ACC_PUBLIC, "apply", staticDesc, null, null)
      bMv.visitCode()
      var bSlot = 1
      for i <- 0 until f.params.length do
        if paramIsRef(i) then { bMv.visitVarInsn(ALOAD, bSlot); bSlot += 1 }
        else if isDouble  then { bMv.visitVarInsn(DLOAD, bSlot); bSlot += 2 }
        else               { bMv.visitVarInsn(LLOAD, bSlot); bSlot += 2 }
      bMv.visitMethodInsn(INVOKESTATIC, cname, staticName, staticDesc, false)
      bMv.visitInsn(if isDouble then DRETURN else LRETURN)
      try bMv.visitMaxs(0, 0) catch case _: Throwable => return null
      bMv.visitEnd()

    cw.visitEnd()
    val bytes  = try cw.toByteArray catch case _: Throwable => return null
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = {
      val c = try loader.define(cname.replace('/', '.'), bytes) catch case _: Throwable => null
      if c == null then return null else c
    }

    val ptypes: Array[Class[?]] = paramIsRef.map(r =>
      if r then classOf[Object] else if isDouble then classOf[Double] else classOf[Long])
    val rtype: Class[?] = if isDouble then classOf[Double] else classOf[Long]
    val mt = java.lang.invoke.MethodType.methodType(rtype, ptypes.asInstanceOf[Array[Class[?]]])
    val mh = {
      val h = try java.lang.invoke.MethodHandles.lookup().findStatic(cls, staticName, mt)
              catch case _: Throwable => null
      if h == null then return null else h
    }
    val direct: AnyRef | Null =
      if ifaceName != null then
        try cls.getConstructor().newInstance().asInstanceOf[AnyRef]
        catch case _: Throwable => null
      else null
    new JitResult(mh, paramIsRef, isDouble, direct, resultIsBool = isBoolReturning(f.body))

  private def tryCompileObjToObject(
    tm:     Term.Match,
    ctx:    GenCtx,
    interp: Interpreter,
    cname:  String
  ): JitResult | Null =
    val ifaceInternal = "scalascript/interpreter/vm/jit/ObjToObject"
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array(ifaceInternal))

    val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(ALOAD, 0)
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(RETURN)
    init.visitMaxs(1, 1)
    init.visitEnd()

    val staticDesc = "(Ljava/lang/Object;)Ljava/lang/Object;"
    val smv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, ctx.staticMethodName, staticDesc, null, null)
    smv.visitCode()
    if !emitRefMatchBody(tm, ctx, interp, smv) then return null
    try smv.visitMaxs(0, 0) catch case _: Throwable => return null
    smv.visitEnd()

    val bMv = cw.visitMethod(ACC_PUBLIC, "apply", staticDesc, null, null)
    bMv.visitCode()
    bMv.visitVarInsn(ALOAD, 1)
    bMv.visitMethodInsn(INVOKESTATIC, cname, ctx.staticMethodName, staticDesc, false)
    bMv.visitInsn(ARETURN)
    try bMv.visitMaxs(0, 0) catch case _: Throwable => return null
    bMv.visitEnd()

    cw.visitEnd()
    val bytes = try cw.toByteArray catch case _: Throwable => return null
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls =
      try loader.define(cname.replace('/', '.'), bytes)
      catch case _: Throwable => return null
    val mt = java.lang.invoke.MethodType.methodType(classOf[Object], classOf[Object])
    val mh =
      try java.lang.invoke.MethodHandles.lookup().findStatic(cls, ctx.staticMethodName, mt)
      catch case _: Throwable => return null
    val direct =
      try cls.getConstructor().newInstance().asInstanceOf[AnyRef]
      catch case _: Throwable => return null
    new JitResult(mh, Array(true), resultIsDouble = false, direct)

  // ── LongToObject: recursive ADT-builder JIT ─────────────────────────────
  // Mirrors JavacJitBackend.walkObject / emitConstructorObject.  Compiles a
  // 1-param Long function whose body builds InstanceV ADT values (e.g.
  // `def build(d: Int): Expr = if d<=0 then Num(1) else Add(build(d-1), …)`)
  // into a `LongToObject` direct interface — no interpreter re-entry per node.

  private val valueModuleInt = "scalascript/interpreter/Value$"

  private def tryCompileLongToObject(
    f:     Value.FunV,
    ctx:   GenCtx,
    cname: String
  ): JitResult | Null =
    val ifaceInternal = "scalascript/interpreter/vm/jit/LongToObject"
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array(ifaceInternal))

    val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(ALOAD, 0)
    init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init.visitInsn(RETURN); init.visitMaxs(1, 1); init.visitEnd()

    val staticDesc = "(J)Ljava/lang/Object;"
    val smv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, ctx.staticMethodName, staticDesc, null, null)
    smv.visitCode()
    if !emitObject(f.body.asInstanceOf[Term], ctx, smv) then return null
    smv.visitInsn(ARETURN)
    try smv.visitMaxs(0, 0) catch case _: Throwable => return null
    smv.visitEnd()

    val bMv = cw.visitMethod(ACC_PUBLIC, "apply", staticDesc, null, null)
    bMv.visitCode()
    bMv.visitVarInsn(LLOAD, 1)
    bMv.visitMethodInsn(INVOKESTATIC, cname, ctx.staticMethodName, staticDesc, false)
    bMv.visitInsn(ARETURN)
    try bMv.visitMaxs(0, 0) catch case _: Throwable => return null
    bMv.visitEnd()

    cw.visitEnd()
    val bytes  = try cw.toByteArray catch case _: Throwable => return null
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls =
      try loader.define(cname.replace('/', '.'), bytes)
      catch case _: Throwable => return null
    val mt = java.lang.invoke.MethodType.methodType(classOf[Object], classOf[Long])
    val mh =
      try java.lang.invoke.MethodHandles.lookup().findStatic(cls, ctx.staticMethodName, mt)
      catch case _: Throwable => return null
    val direct =
      try cls.getConstructor().newInstance().asInstanceOf[AnyRef]
      catch case _: Throwable => return null
    new JitResult(mh, Array(false), resultIsDouble = false, direct)

  /** Emit bytecode that leaves one `Object` (an InstanceV or recursive result)
   *  on the stack. Mirrors javac `walkObject`. Returns false on unsupported
   *  shapes so the caller can fall through to the Long path. */
  private def emitObject(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Term.Name("None") =>
      mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
      mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "NoneV", s"()L$optionVInt;", false)
      true
    // Stage 8: s"..." interpolation and ref + concat in LongToObject body — delegate to walkRef.
    case _: Term.Interpolate => walkRef(t, ctx, mv)
    case Term.ApplyInfix.After_4_6_0(_, op, _, _) if op.value == "+" =>
      walkRef(t, ctx, mv)
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => emitObject(inner, ctx, mv)
        case _           => false
    case ti: Term.If =>
      val Lelse = new Label; val Lend = new Label
      if !walkBool(ti.cond, ctx, mv, Lelse) then return false  // jump to else when cond false
      if !emitObject(ti.thenp, ctx, mv) then return false
      mv.visitJumpInsn(GOTO, Lend)
      mv.visitLabel(Lelse)
      if !emitObject(ti.elsep, ctx, mv) then return false
      mv.visitLabel(Lend)
      true
    case Term.Select(recv: Term, Term.Name(method)) if isNumericObjectReceiver(recv) =>
      emitNumericObjectMethod(recv, method, Nil, ctx, mv)
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" && ap.argClause.values.lengthCompare(1) == 0 =>
          mv.visitTypeInsn(NEW, optionVInt)
          mv.visitInsn(DUP)
          if !emitValueObject(ap.argClause.values.head, ctx, mv) then return false
          mv.visitMethodInsn(INVOKESPECIAL, optionVInt, "<init>", s"(L$valueInt;)V", false)
          true
        case fn: Term.Name if (fn.value == "Right" || fn.value == "Left") && ap.argClause.values.lengthCompare(1) == 0 =>
          emitBuiltinEitherObject(fn.value, ap.argClause.values.head, ctx, mv)
        case fn: Term.Name if fn.value == ctx.funName =>
          if ap.argClause.values.length != ctx.paramNames.length then return false
          var rem = ap.argClause.values
          while rem.nonEmpty do
            if !walkLong(rem.head, ctx, mv) then return false
            rem = rem.tail
          val selfDesc = "(" + ("J" * ctx.paramNames.length) + ")Ljava/lang/Object;"
          mv.visitMethodInsn(INVOKESTATIC, ctx.selfClass, ctx.staticMethodName, selfDesc, false)
          true
        case ctor: Term.Name if ctx.interp.typeFieldOrder.contains(ctor.value) =>
          emitConstructorObject(ctor.value, ap.argClause.values, ctx, mv)
        case ctor: Term.Name if ctor.value == "BigInt" || ctor.value == "Decimal" =>
          emitNumericObjectValue(ap, ctx, mv)
        case Term.Select(recv: Term, Term.Name(method)) if isNumericObjectReceiver(recv) =>
          emitNumericObjectMethod(recv, method, ap.argClause.values, ctx, mv)
        case _ if objectRefFallbackAllowed(ap, ctx) => walkRef(ap, ctx, mv)
        case _ => false
    case _ if objectRefFallbackAllowed(t, ctx) => walkRef(t, ctx, mv)
    case _ => false

  /** Build an InstanceV for `typeName(args…)` and leave it on the stack.
   *  Mirrors javac `emitConstructorObject` + the `__inst` helper. */
  private def emitConstructorObject(typeName: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    val fieldNames = ctx.interp.typeFieldOrder.getOrElse(typeName, Nil)
    if fieldNames.length != args.length then return false
    val fieldTypes = ctx.interp.typeFieldTypes.getOrElse(typeName, Nil)
    val tag        = ctx.interp.typeTagMap.getOrElse(typeName, 0)

    // new InstanceV(typeName.intern(), Map$.empty())
    mv.visitTypeInsn(NEW, instVInt)
    mv.visitInsn(DUP)
    mv.visitLdcInsn(typeName)
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "intern", "()Ljava/lang/String;", false)
    mv.visitFieldInsn(GETSTATIC, "scala/collection/immutable/Map$", "MODULE$", "Lscala/collection/immutable/Map$;")
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/Map$", "empty", "()Lscala/collection/immutable/Map;", false)
    mv.visitMethodInsn(INVOKESPECIAL, instVInt, "<init>",
      "(Ljava/lang/String;Lscala/collection/immutable/Map;)V", false)
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, instSlot)

    // fieldsArr = new Value[]{ … }
    mv.visitVarInsn(ALOAD, instSlot)
    emitIconst(mv, args.length)
    mv.visitTypeInsn(ANEWARRAY, valueInt)
    var i    = 0
    var rest = args
    while rest.nonEmpty do
      val ft = if i < fieldTypes.length then fieldTypes(i) else ""
      mv.visitInsn(DUP)
      emitIconst(mv, i)
      if isPrimitiveFieldType(ft) then
        if !emitPrimitiveValue(rest.head, ft, ctx, mv) then return false
      else if !emitObject(rest.head, ctx, mv) then return false
      mv.visitInsn(AASTORE)
      i += 1
      rest = rest.tail
    mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr_$eq", s"([L$valueInt;)V", false)

    // fieldNames = new String[]{ … }
    mv.visitVarInsn(ALOAD, instSlot)
    emitIconst(mv, fieldNames.length)
    mv.visitTypeInsn(ANEWARRAY, "java/lang/String")
    var j    = 0
    var nrem = fieldNames
    while nrem.nonEmpty do
      mv.visitInsn(DUP)
      emitIconst(mv, j)
      mv.visitLdcInsn(nrem.head)
      mv.visitInsn(AASTORE)
      j += 1
      nrem = nrem.tail
    mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldNames_$eq", "([Ljava/lang/String;)V", false)

    // typeTag = tag
    mv.visitVarInsn(ALOAD, instSlot)
    emitIconst(mv, tag)
    mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag_$eq", "(I)V", false)

    // leave the InstanceV on the stack as the result
    mv.visitVarInsn(ALOAD, instSlot)
    true

  private def isPrimitiveFieldType(t: String): Boolean =
    t == "Int" || t == "Long" || t == "Double" || t == "Boolean" || t == "String"

  private def emitValueObject(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean =
    if isNumericObjectValueShape(t) then
      emitNumericObjectValue(t, ctx, mv)
    else
      t match
        case Lit.String(v) =>
          mv.visitTypeInsn(NEW, stringVInt)
          mv.visitInsn(DUP)
          mv.visitLdcInsn(v)
          mv.visitMethodInsn(INVOKESPECIAL, stringVInt, "<init>", "(Ljava/lang/String;)V", false)
          true
        case Lit.Boolean(b) =>
          mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
          mv.visitInsn(if b then ICONST_1 else ICONST_0)
          mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "boolV", s"(Z)L$boolVInt;", false)
          true
        case _ =>
          mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
          if !walkLong(t, ctx, mv) then return false
          mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "intV", s"(J)L$intVInt;", false)
          true

  /** Emit a boxed `Value` for a primitive ADT field (Int/Long/Double).
   *  Boolean is intentionally unsupported (no value-producing bool walker);
   *  callers fall through to the interpreter for such constructors. */
  private def emitPrimitiveValue(t: Term, fieldType: String, ctx: GenCtx, mv: MethodVisitor): Boolean =
    fieldType match
      case "Int" | "Long" =>
        mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
        if !walkLong(t, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "intV", s"(J)L$intVInt;", false)
        true
      case "Double" =>
        mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
        if !walkDouble(t, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "doubleV", s"(D)L$dblVInt;", false)
        true
      case "String" =>
        emitValueObject(t, ctx, mv)
      case _ => false

  private def isRefValRhs(t: Term, ctx: GenCtx): Boolean = t match
    case Term.Name("None") => true
    case tn: Term.Name     => ctx.isRefName(tn.value)
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => isRefValRhs(inner, ctx)
        case _           => false
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" =>
          ap.argClause.values.lengthCompare(1) == 0
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

  /** Direction A.5: process non-final `Defn.Val` stats, emitting LSTORE/DSTORE
   *  for primitive locals and ASTORE for ref-valued immutable locals. Returns
   *  the updated GenCtx with the new bindings on success, or null if any stat is
   *  not a compilable val-binding. */
  private def emitValBindings(stats: List[Stat], ctx: GenCtx, mv: MethodVisitor): GenCtx | Null =
    var curCtx = ctx
    var rem = stats
    while rem.nonEmpty do
      rem.head match
        case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
          val slot = curCtx.allocSlot()
          if isRefValRhs(rhs, curCtx) then
            if !walkRef(rhs, curCtx, mv) then return null
            mv.visitVarInsn(ASTORE, slot)
            curCtx = curCtx.withBindings(Map(n.value -> (slot, true)))
          else
            val ok = if curCtx.isDouble then walkDouble(rhs, curCtx, mv) else walkLong(rhs, curCtx, mv)
            if !ok then return null
            mv.visitVarInsn(if curCtx.isDouble then DSTORE else LSTORE, slot)
            curCtx = curCtx.withBindings(Map(n.value -> (slot, false)))
        case _ => return null
      rem = rem.tail
    curCtx

  private def emitBlockExpr(stats: List[Stat], ctx: GenCtx, mv: MethodVisitor, isDouble: Boolean): Boolean =
    if stats.isEmpty then return false
    val tailCtx = emitValBindings(stats.init, ctx, mv)
    if tailCtx == null then return false
    stats.last match
      case last: Term =>
        if isDouble then walkDouble(last, tailCtx, mv)
        else walkLong(last, tailCtx, mv)
      case _ => false

  /** Emit one void statement inside a while body: an assignment to a bound local
   *  slot, a local `var`/`val` declaration, a nested `while`, or a discarded
   *  long/double expression. Returns the (possibly extended) context to thread
   *  into later body statements, or null on failure. Mirrors javac
   *  `walkStatAsVoid`. */
  private def emitStatAsVoid(stat: Stat, ctx: GenCtx, mv: MethodVisitor): GenCtx | Null = stat match
    case Term.Assign(nm: Term.Name, rhs: Term) =>
      val slot = ctx.slotOf(nm.value)
      if slot < 0 || ctx.isRefName(nm.value) then return null
      val ok = if ctx.isDouble then walkDouble(rhs, ctx, mv) else walkLong(rhs, ctx, mv)
      if !ok then return null
      mv.visitVarInsn(if ctx.isDouble then DSTORE else LSTORE, slot)
      ctx
    case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs: Term) =>
      val slot = ctx.allocSlot()
      val ok = if ctx.isDouble then walkDouble(rhs, ctx, mv) else walkLong(rhs, ctx, mv)
      if !ok then return null
      mv.visitVarInsn(if ctx.isDouble then DSTORE else LSTORE, slot)
      ctx.withBindings(Map(n.value -> (slot, false)))
    case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
      val slot = ctx.allocSlot()
      if isRefValRhs(rhs, ctx) then
        if !walkRef(rhs, ctx, mv) then return null
        mv.visitVarInsn(ASTORE, slot)
        ctx.withBindings(Map(n.value -> (slot, true)))
      else
        val ok = if ctx.isDouble then walkDouble(rhs, ctx, mv) else walkLong(rhs, ctx, mv)
        if !ok then return null
        mv.visitVarInsn(if ctx.isDouble then DSTORE else LSTORE, slot)
        ctx.withBindings(Map(n.value -> (slot, false)))
    case w: Term.While =>
      if !emitWhileAsStmt(w, ctx, mv) then return null
      ctx
    case ti: Term.If =>
      // Void if-statement: `if cond then body` with Unit/empty else.
      val isVoidElse = ti.elsep match
        case _: Lit.Unit | Term.Block(Nil) => true; case _ => false
      if !isVoidElse then return null
      val Lend = new Label
      if !walkBool(ti.cond, ctx, mv, Lend) then return null
      val bodyStats: List[Stat] = ti.thenp match
        case Term.Block(ss) => ss; case s: Stat => List(s)
      var curCtx = ctx
      var rem = bodyStats
      var ok = true
      while rem.nonEmpty && ok do
        val nc = emitStatAsVoid(rem.head, curCtx, mv)
        if nc == null then ok = false else curCtx = nc
        rem = rem.tail
      if !ok then return null
      mv.visitLabel(Lend)
      ctx
    case e: Term =>
      val ok = if ctx.isDouble then walkDouble(e, ctx, mv) else walkLong(e, ctx, mv)
      if !ok then return null
      mv.visitInsn(POP2)   // discard long/double result (both 2-wide)
      ctx
    case _ => null

  /** Emit a `Term.While` appearing as a non-final body statement. Body stats are
   *  emitted as void (no return), threading bindings so an inner `var` is visible
   *  to later stats and to a nested `while`. Mirrors javac `walkWhileAsStmt`. */
  private def emitWhileAsStmt(w: Term.While, ctx: GenCtx, mv: MethodVisitor): Boolean =
    val bodyStats: List[Stat] = w.body match
      case Term.Block(ss) => ss
      case s: Stat        => List(s)
    val Lhead = new Label; val Lend = new Label
    mv.visitLabel(Lhead)
    if !walkBool(w.expr, ctx, mv, Lend) then return false  // exit loop when cond is false
    var curCtx = ctx
    val it = bodyStats.iterator
    while it.hasNext do
      val nc = emitStatAsVoid(it.next(), curCtx, mv)
      if nc == null then return false
      curCtx = nc
    mv.visitJumpInsn(GOTO, Lhead)
    mv.visitLabel(Lend)
    true

  /** Emit a multi-statement function body: val/var bindings and while loops as
   *  local-slot bytecode, then `return <final expr>`. Mirrors javac
   *  `walkBlockStmts` and is what closes the effect-pure gap (a function whose
   *  body is `var…; var…; while…; <expr>`). */
  private def emitBlockStmts(stats: List[Stat], ctx: GenCtx, mv: MethodVisitor): Boolean =
    var curCtx = ctx
    var rem    = stats
    while rem.nonEmpty do
      val stat = rem.head
      rem = rem.tail
      if rem.isEmpty then
        stat match
          case last: Term =>
            val ok = if curCtx.isDouble then walkDouble(last, curCtx, mv) else walkLong(last, curCtx, mv)
            if !ok then return false
            mv.visitInsn(if curCtx.isDouble then DRETURN else LRETURN)
          case _ => return false
      else
        val newCtx = emitStatAsVoid(stat, curCtx, mv)
        if newCtx == null then return false
        curCtx = newCtx
    true

  private def emitFnBody(f: Value.FunV, ctx: GenCtx, interp: Interpreter,
                         mv: MethodVisitor, isDouble: Boolean): Boolean =
    f.body match
      case tm: Term.Match if isTupleMatch(tm) =>
        emitTupleMatchBody(tm, ctx, interp, mv, isDouble)
      case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
        emitMatchBody(tm, ctx, interp, mv)
      case tm: Term.Match if isLiteralIntMatch(tm) =>
        emitLiteralIntMatch(tm, ctx, mv, returnForm = true, endLbl = null)
      case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
        // Direction A.5: multi-stat block — emit val-bindings then the final expr.
        b.stats.last match
          case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
            val tailCtx = emitValBindings(b.stats.init, ctx, mv)
            tailCtx != null && emitMatchBody(tm, tailCtx, interp, mv)
          case _: Term =>
            // A.5 + effect-pure: val/var bindings and while loops as local-slot
            // bytecode, then `return <final expr>` (emitBlockStmts emits return).
            emitBlockStmts(b.stats, ctx, mv)
          case _ => false
      case Term.Try.After_4_9_9(tryExpr: Term, Some(handler), None) if handler.cases.nonEmpty =>
        emitTryCatchBody(tryExpr, handler.cases, ctx, mv, isDouble)
      case other =>
        val tco = tryTcoBody(other.asInstanceOf[Term], ctx, mv)
        if tco then true
        else if isDouble then
          walkDouble(other.asInstanceOf[Term], ctx, mv) && { mv.visitInsn(DRETURN); true }
        else
          val t = other.asInstanceOf[Term]
          if walkLong(t, ctx, mv) then { mv.visitInsn(LRETURN); true }
          else
            // Bool-body fallback: emit `if cond then 1L else 0L` for predicate fns.
            val Lfalse = new Label; val Lend = new Label
            if !walkBool(t, ctx, mv, Lfalse) then false
            else
              mv.visitLdcInsn(1L); mv.visitJumpInsn(GOTO, Lend)
              mv.visitLabel(Lfalse); mv.visitLdcInsn(0L)
              mv.visitLabel(Lend);   mv.visitInsn(LRETURN)
              true

  private def emitTryCatchBody(tryExpr: Term, cases: List[scala.meta.Case], ctx: GenCtx,
                               mv: MethodVisitor, isDouble: Boolean): Boolean =
    val startLbl = new Label
    val endLbl   = new Label
    // Pre-validate all catch patterns and build (catchLbl, bindSlot, catchCtx, body) tuples.
    val casesArr = cases.toArray
    val catchLbls    = Array.fill(casesArr.length)(new Label)
    val catchSlots   = new Array[Int](casesArr.length)
    val catchCtxArr  = new Array[GenCtx](casesArr.length)
    val catchBodies  = new Array[Term](casesArr.length)
    var ci = 0
    while ci < casesArr.length do
      val c = casesArr(ci)
      val (bindSlot, catchCtx) = c.pat match
        case _: Pat.Wildcard => (-1, ctx)
        case pv: Pat.Var =>
          val s = ctx.allocSlot()
          (s, ctx.withBindings(Map(pv.name.value -> (s, true))))
        case Pat.Typed(_: Pat.Wildcard, _) => (-1, ctx)
        case Pat.Typed(pv: Pat.Var, _) =>
          val s = ctx.allocSlot()
          (s, ctx.withBindings(Map(pv.name.value -> (s, true))))
        case _ => return false
      catchSlots(ci)  = bindSlot
      catchCtxArr(ci) = catchCtx
      catchBodies(ci) = c.body.asInstanceOf[Term]
      ci += 1
    // Register each catch handler for the try region.
    ci = 0
    while ci < casesArr.length do
      mv.visitTryCatchBlock(startLbl, endLbl, catchLbls(ci), "java/lang/Exception")
      ci += 1
    mv.visitLabel(startLbl)
    val tryOk = if isDouble then walkDouble(tryExpr, ctx, mv) && { mv.visitInsn(DRETURN); true }
                else walkLong(tryExpr, ctx, mv) && { mv.visitInsn(LRETURN); true }
    if !tryOk then return false
    mv.visitLabel(endLbl)
    ci = 0
    while ci < casesArr.length do
      mv.visitLabel(catchLbls(ci))
      if catchSlots(ci) >= 0 then mv.visitVarInsn(ASTORE, catchSlots(ci))
      else mv.visitInsn(POP)
      val catchOk =
        if isDouble then walkDouble(catchBodies(ci), catchCtxArr(ci), mv) && { mv.visitInsn(DRETURN); true }
        else walkLong(catchBodies(ci), catchCtxArr(ci), mv) && { mv.visitInsn(LRETURN); true }
      if !catchOk then return false
      ci += 1
    true

  // ── Descriptor builder ────────────────────────────────────────────────────

  private def buildStaticDesc(paramIsRef: Array[Boolean], isDouble: Boolean): String =
    val sb = new StringBuilder("(")
    for r <- paramIsRef do
      if r then sb.append("Ljava/lang/Object;")
      else if isDouble then sb.append("D") else sb.append("J")
    sb.append(if isDouble then ")D" else ")J")
    sb.toString

  private def buildRefDesc(paramIsRef: Array[Boolean]): String =
    val sb = new StringBuilder("(")
    for r <- paramIsRef do
      if r then sb.append("Ljava/lang/Object;") else sb.append("J")
    sb.append(")Ljava/lang/Object;")
    sb.toString

  // ── Walker context ────────────────────────────────────────────────────────

  private case class GenCtx(
    funName:          String,
    staticMethodName: String,
    params:           Set[String],
    paramNames:       Array[String],
    paramSlots:       Array[Int],
    paramIsRef:       Array[Boolean],
    isDouble:         Boolean,
    bindings:         Map[String, (Int, Boolean)],  // name → (slot, isRef)
    interp:           Interpreter,
    coEmit:           CoEmitState,
    selfClass:        String,
    allocSlot:        () => Int,
    paramTypes:       Array[String] = Array.empty
  ):
    def isRefName(n: String): Boolean =
      bindings.get(n) match
        case Some((_, r)) => r
        case None => val i = paramNames.indexOf(n); i >= 0 && paramIsRef(i)

    /** Declared type of a ref param (e.g. "Vec"), or null if unknown. */
    def refTypeOf(n: String): String | Null =
      val i = paramNames.indexOf(n)
      if i >= 0 && i < paramTypes.length then paramTypes(i) else null

    def slotOf(n: String): Int =
      bindings.get(n) match
        case Some((s, _)) => s
        case None => val i = paramNames.indexOf(n); if i >= 0 then paramSlots(i) else -1

    def isParam(n: String): Boolean = params.contains(n) || bindings.contains(n)

    /** True iff `n` is a `String`-typed param (used by `walkString`). Pattern
     *  bindings are never String-typed in the current lane. */
    def isStringName(n: String): Boolean =
      !bindings.contains(n) && {
        val i = paramNames.indexOf(n)
        i >= 0 && i < paramTypes.length && paramTypes(i) == "String"
      }

    def withBindings(more: Map[String, (Int, Boolean)]): GenCtx = copy(bindings = bindings ++ more)

  // ── walkLong ─────────────────────────────────────────────────────────────

  private def walkLong(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Lit.Int(v)     => mv.visitLdcInsn(v.toLong); true
    case Lit.Long(v)    => mv.visitLdcInsn(v);        true
    case Lit.Boolean(b) => mv.visitLdcInsn(if b then 1L else 0L); true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkLong(inner, ctx, mv)
        case _           => false
    case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
      emitBlockExpr(b.stats, ctx, mv, isDouble = false)
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx, mv)
    case tn: Term.Name =>
      if ctx.isRefName(tn.value) then return false
      val s = ctx.slotOf(tn.value)
      if s >= 0 then { mv.visitVarInsn(LLOAD, s); true }
      else
        ctx.interp.globals.getOrElse(tn.value, null) match
          case Value.IntV(v) if ctx.interp.valNames.contains(tn.value) =>
            mv.visitLdcInsn(v); true
          case _: Value.IntV => emitGlobalLong(tn.value, mv); true
          case _             => false
    case ti: Term.If =>
      val Le = new Label; val Ld = new Label
      walkBool(ti.cond, ctx, mv, Le) &&
        walkLong(ti.thenp, ctx, mv) && { mv.visitJumpInsn(GOTO, Ld); mv.visitLabel(Le); true } &&
        walkLong(ti.elsep, ctx, mv) && { mv.visitLabel(Ld); true }
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac) if ac.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" =>
          walkLong(lhs, ctx, mv) && walkLong(ac.values.head, ctx, mv) && {
            mv.visitInsn(longArith(op.value)); true }
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val Lt = new Label; val Le = new Label
          walkLong(lhs, ctx, mv) && walkLong(ac.values.head, ctx, mv) && {
            mv.visitInsn(LCMP)
            mv.visitJumpInsn(cmpJump(op.value), Lt)
            mv.visitLdcInsn(0L); mv.visitJumpInsn(GOTO, Le)
            mv.visitLabel(Lt); mv.visitLdcInsn(1L); mv.visitLabel(Le); true }
        case _ => false
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      walkLong(arg, ctx, mv) && {
        if op.value == "-" then mv.visitInsn(LNEG)
        true
      }
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      emitRefFieldNumeric(objName, field, ctx, mv, wantDouble = false)
    case Term.Select(recv: Term, Term.Name("size")) =>
      emitRefChainLong(recv, "size", Nil, ctx, mv)
    case Term.Select(recv: Term, Term.Name("head")) =>
      emitRefChainLong(recv, "head", Nil, ctx, mv)
    // `<stringExpr>.length` → push the String, INVOKEVIRTUAL length()I, widen to long.
    case Term.Select(recv, Term.Name("length")) =>
      if !walkString(recv, ctx, mv) then false
      else
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)
        mv.visitInsn(I2L)
        true
    // `.toLong` / `.toInt` are no-ops in ScalaScript (Int = Long = JVM long).
    case Term.Select(inner: Term, Term.Name("toLong" | "toInt")) =>
      walkLong(inner, ctx, mv)
    // `.toDouble` on a Long expression — widen.
    case Term.Select(inner: Term, Term.Name("toDouble")) =>
      walkLong(inner, ctx, mv) && { mv.visitInsn(L2D); true }
    case ap: Term.Apply =>
      ap.fun match
        case inner: Term.Apply =>
          emitHofFoldLeftLong(inner, ap.argClause.values, ctx, mv)
        case Term.Select(recv: Term, Term.Name(method)) =>
          emitHofFoldLong(recv, method, ap.argClause.values, ctx, mv) ||
            emitRefChainLong(recv, method, ap.argClause.values, ctx, mv)
        case fn: Term.Name =>
          emitLongCall(fn.value, ap.argClause.values, ctx, mv)
        case _ => false
    case _ => false

  private def emitRefChainLong(recv: Term, method: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    method match
      case "getOrElse" if args.lengthCompare(1) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        if !walkLong(args.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "getOrElseLong", "(Ljava/lang/Object;J)J", false)
        true
      case "size" if args.isEmpty =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "sizeLong", "(Ljava/lang/Object;)J", false)
        true
      case "head" if args.isEmpty =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "headLong", "(Ljava/lang/Object;)J", false)
        true
      case _ => false

  private def emitRefChainObject(recv: Term, method: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    if isNumericObjectReceiver(recv) then return emitNumericObjectMethod(recv, method, args, ctx, mv)
    method match
      case "getOrElse" if args.lengthCompare(1) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        if !emitValueObject(args.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "getOrElseRef", s"(Ljava/lang/Object;L$valueInt;)Ljava/lang/Object;", false)
        true
      case "getOrElse" if args.lengthCompare(2) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        if !emitValueObject(args.head, ctx, mv) then return false
        if !emitValueObject(args(1), ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "mapGetOrElseRef", s"(Ljava/lang/Object;L$valueInt;L$valueInt;)Ljava/lang/Object;", false)
        true
      case "mkString" if args.isEmpty =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "mkStringRef", "(Ljava/lang/Object;)Ljava/lang/Object;", false)
        true
      case "mkString" if args.lengthCompare(1) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        if !walkString(args.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "mkStringRef", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false)
        true
      case "mkString" if args.lengthCompare(3) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        if !walkString(args.head, ctx, mv) then return false
        if !walkString(args(1), ctx, mv) then return false
        if !walkString(args(2), ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "mkStringRef", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false)
        true
      case _ => false

  private def isNumericObjectReceiver(recv: Term): Boolean =
    isNumericObjectValueShape(recv)

  private def isNumericObjectValueShape(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Name("BigInt"), argClause) =>
      argClause.values.lengthCompare(1) == 0
    case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause) =>
      argClause.values.lengthCompare(1) == 0 || argClause.values.lengthCompare(2) == 0
    case _ => false

  private def emitNumericObjectValue(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean =
    t match
      case Term.Apply.After_4_6_0(Term.Name("BigInt"), argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !emitValueObject(argClause.values.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntRef", s"(L$valueInt;)L$valueInt;", false)
        true
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !emitValueObject(argClause.values.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalRef", s"(L$valueInt;)L$valueInt;", false)
        true
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause)
          if argClause.values.lengthCompare(2) == 0 =>
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !emitValueObject(argClause.values.head, ctx, mv) then return false
        if !walkLong(argClause.values(1), ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalRef", s"(L$valueInt;J)L$valueInt;", false)
        true
      case _ => false

  private def emitNumericObjectMethod(
    recv:   Term,
    method: String,
    args:   List[Term],
    ctx:    GenCtx,
    mv:     MethodVisitor
  ): Boolean =
    recv match
      case Term.Apply.After_4_6_0(Term.Name("BigInt"), _) =>
        method match
          case "abs" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntAbs", s"(L$valueInt;)L$valueInt;", false)
            true
          case "negate" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntNegate", s"(L$valueInt;)L$valueInt;", false)
            true
          case "pow" if args.lengthCompare(1) == 0 =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            if !walkLong(args.head, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntPow", s"(L$valueInt;J)L$valueInt;", false)
            true
          case "gcd" if args.lengthCompare(1) == 0 =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            if !emitValueObject(args.head, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntGcd", s"(L$valueInt;L$valueInt;)L$valueInt;", false)
            true
          case "toDecimal" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "bigIntToDecimal", s"(L$valueInt;)L$valueInt;", false)
            true
          case _ => false
      case Term.Apply.After_4_6_0(Term.Name("Decimal"), _) =>
        method match
          case "abs" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalAbs", s"(L$valueInt;)L$valueInt;", false)
            true
          case "negate" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalNegate", s"(L$valueInt;)L$valueInt;", false)
            true
          case "pow" if args.lengthCompare(1) == 0 =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            if !walkLong(args.head, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalPow", s"(L$valueInt;J)L$valueInt;", false)
            true
          case "setScale" if args.lengthCompare(1) == 0 =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            if !walkLong(args.head, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalSetScale", s"(L$valueInt;J)L$valueInt;", false)
            true
          case "toBigInt" if args.isEmpty =>
            mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
            if !emitNumericObjectValue(recv, ctx, mv) then return false
            mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "decimalToBigInt", s"(L$valueInt;)L$valueInt;", false)
            true
          case _ => false
      case _ => false

  private def objectRefFallbackAllowed(t: Term, ctx: GenCtx): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("mkString")), argClause) =>
      val args = argClause.values
      args.isEmpty || args.lengthCompare(1) == 0 || args.lengthCompare(3) == 0
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(2) == 0 =>
      true
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      !looksLongValue(argClause.values.head, ctx)
    case _ => false

  private def looksLongValue(t: Term, ctx: GenCtx): Boolean = t match
    case Lit.Int(_) | Lit.Long(_) | Lit.Boolean(_) => true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => looksLongValue(inner, ctx)
        case _           => false
    case ti: Term.If =>
      looksLongValue(ti.thenp, ctx) && looksLongValue(ti.elsep, ctx)
    case tn: Term.Name =>
      !ctx.isRefName(tn.value) && !ctx.isStringName(tn.value) &&
        (ctx.slotOf(tn.value) >= 0 ||
          ctx.interp.globals.get(tn.value).exists(_.isInstanceOf[Value.IntV]))
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      looksLongValue(arg, ctx)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" | "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          looksLongValue(lhs, ctx) && looksLongValue(argClause.values.head, ctx)
        case _ => false
    case Term.Select(_: Term, Term.Name("size" | "head" | "length")) =>
      true
    case Term.Select(inner: Term, Term.Name("toLong" | "toInt")) =>
      looksLongValue(inner, ctx)
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      looksLongValue(argClause.values.head, ctx)
    case _ => false

  private def emitHofRefChain(recv: Term, method: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    method match
      case "map" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val op = JitHofShape.unaryLong(fn)
            if op == null then return false
            mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
            if !walkRef(recv, ctx, mv) then return false
            emitIconst(mv, op.op)
            mv.visitLdcInsn(op.c)
            mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "mapLong", "(Ljava/lang/Object;IJ)Ljava/lang/Object;", false)
            true
          case _ => false
      case "flatMap" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val name = JitHofShape.globalLong(fn)
            if name == null then return false
            mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
            if !walkRef(recv, ctx, mv) then return false
            mv.visitLdcInsn(name)
            mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "flatMapGlobalLong", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false)
            true
          case _ => false
      case "filter" if args.lengthCompare(1) == 0 =>
        args.head match
          case fn: Term.Function =>
            val pred = JitHofShape.predicateLong(fn)
            if pred == null then return false
            mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
            if !walkRef(recv, ctx, mv) then return false
            emitIconst(mv, pred.pred)
            mv.visitLdcInsn(pred.c1)
            mv.visitLdcInsn(pred.c2)
            mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "filterLong", "(Ljava/lang/Object;IJJ)Ljava/lang/Object;", false)
            true
          case _ => false
      case _ => false

  private def emitHofFoldLong(recv: Term, method: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    if method != "fold" || args.lengthCompare(2) != 0 then return false
    (args.head, args(1)) match
      case (left: Term.Function, right: Term.Function) =>
        val leftConst = JitHofShape.constantLong(left)
        val rightOp   = JitHofShape.unaryLong(right)
        if leftConst == null || rightOp == null then return false
        mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
        if !walkRef(recv, ctx, mv) then return false
        mv.visitLdcInsn(leftConst.longValue)
        emitIconst(mv, rightOp.op)
        mv.visitLdcInsn(rightOp.c)
        mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "foldLong", "(Ljava/lang/Object;JIJ)J", false)
        true
      case _ => false

  private def emitHofFoldLeftLong(inner: Term.Apply, outerArgs: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    if outerArgs.lengthCompare(1) != 0 then return false
    inner.fun match
      case Term.Select(recv: Term, Term.Name("foldLeft")) if inner.argClause.values.lengthCompare(1) == 0 =>
        outerArgs.head match
          case fn: Term.Function if JitHofShape.foldAdd(fn) =>
            mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
            if !walkRef(recv, ctx, mv) then return false
            if !walkLong(inner.argClause.values.head, ctx, mv) then return false
            emitIconst(mv, JitHofDispatch.FoldAdd)
            mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "foldLeftLong", "(Ljava/lang/Object;JI)J", false)
            true
          case _ => false
      case _ => false

  private def emitBuiltinEitherObject(typeName: String, arg: Term, ctx: GenCtx, mv: MethodVisitor): Boolean =
    if typeName != "Right" && typeName != "Left" then return false
    mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
    mv.visitLdcInsn(typeName)
    if !emitValueObject(arg, ctx, mv) then return false
    mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "eitherValue", s"(Ljava/lang/String;L$valueInt;)Ljava/lang/Object;", false)
    true

  /** Emit InstanceV field access `obj.field` on a ref param, leaving a primitive
   *  (long if !wantDouble, double if wantDouble) on the stack.  Resolves the
   *  field index from the param's declared type via `typeFieldOrder`, reads
   *  `fieldsArr[idx]` (Map fallback when the array is null), and unboxes.
   *  Returns false (caller falls back to the interpreter) when the type/field
   *  is unknown or the field's primitive kind does not match `wantDouble`. */
  private def emitRefFieldNumeric(objName: String, field: String, ctx: GenCtx,
                                  mv: MethodVisitor, wantDouble: Boolean): Boolean =
    val tn = ctx.refTypeOf(objName)
    if tn == null then return false
    val fo  = ctx.interp.typeFieldOrder.getOrElse(tn, Nil)
    val idx = fo.indexOf(field)
    if idx < 0 then return false
    val ft    = ctx.interp.typeFieldTypes.getOrElse(tn, Nil)
    val ftype = if idx < ft.length then ft(idx) else ""
    if wantDouble then { if ftype != "Double" then return false }
    else if ftype != "Int" && ftype != "Long" then return false
    val slot  = ctx.slotOf(objName)
    if slot < 0 then return false
    // The param slot is typed Object; re-bind it as InstanceV in a fresh slot
    // (via CHECKCAST before ASTORE) so the verifier accepts the InstanceV
    // virtual calls in fieldsArr/emitMapApply.
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ALOAD, slot)
    mv.visitTypeInsn(CHECKCAST, instVInt)
    mv.visitVarInsn(ASTORE, instSlot)
    val faSlot = ctx.allocSlot()
    mv.visitVarInsn(ALOAD, instSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
    mv.visitVarInsn(ASTORE, faSlot)
    emitLoadField(mv, instSlot, faSlot, idx, field, hasArr = true)  // leaves Value on stack
    if wantDouble then
      mv.visitTypeInsn(CHECKCAST, dblVInt)
      mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
    else
      mv.visitTypeInsn(CHECKCAST, intVInt)
      mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
    true

  // ── walkDouble ────────────────────────────────────────────────────────────

  private def walkDouble(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Lit.Double(v) => mv.visitLdcInsn(java.lang.Double.parseDouble(v.toString)); true
    case Lit.Int(v)    => mv.visitLdcInsn(v.toDouble); true
    case Lit.Long(v)   => mv.visitLdcInsn(v.toDouble); true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkDouble(inner, ctx, mv)
        case _           => false
    case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
      emitBlockExpr(b.stats, ctx, mv, isDouble = true)
    case tm: Term.Match =>
      walkMatchExpr(tm, ctx, mv)
    case tn: Term.Name =>
      if ctx.isRefName(tn.value) then return false
      val s = ctx.slotOf(tn.value)
      if s >= 0 then { mv.visitVarInsn(DLOAD, s); true }
      else
        ctx.interp.globals.getOrElse(tn.value, null) match
          case Value.DoubleV(v) if ctx.interp.valNames.contains(tn.value) =>
            mv.visitLdcInsn(v); true
          case _: Value.DoubleV => emitGlobalDouble(tn.value, mv); true
          case _                => false
    case ti: Term.If =>
      val Le = new Label; val Ld = new Label
      walkBool(ti.cond, ctx, mv, Le) &&
        walkDouble(ti.thenp, ctx, mv) && { mv.visitJumpInsn(GOTO, Ld); mv.visitLabel(Le); true } &&
        walkDouble(ti.elsep, ctx, mv) && { mv.visitLabel(Ld); true }
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac) if ac.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" =>
          walkDouble(lhs, ctx, mv) && walkDouble(ac.values.head, ctx, mv) && {
            mv.visitInsn(dblArith(op.value)); true }
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val Lt = new Label; val Le = new Label
          walkDouble(lhs, ctx, mv) && walkDouble(ac.values.head, ctx, mv) && {
            mv.visitInsn(DCMPG)
            mv.visitJumpInsn(cmpJump(op.value), Lt)
            mv.visitLdcInsn(0.0); mv.visitJumpInsn(GOTO, Le)
            mv.visitLabel(Lt); mv.visitLdcInsn(1.0); mv.visitLabel(Le); true }
        case _ => false
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      walkDouble(arg, ctx, mv) && {
        if op.value == "-" then mv.visitInsn(DNEG)
        true
      }
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      emitRefFieldNumeric(objName, field, ctx, mv, wantDouble = true)
    case Term.Select(inner: Term, Term.Name("toDouble")) =>
      walkLong(inner, ctx, mv) && { mv.visitInsn(L2D); true }
    case Term.Select(inner: Term, Term.Name("toFloat")) =>
      walkLong(inner, ctx, mv) && { mv.visitInsn(L2F); mv.visitInsn(F2D); true }
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == ctx.funName =>
          emitSelfCall(ap.argClause.values, ctx, mv)
        case _ => false
    case _ => false

  private def emitSelfCall(args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    if args.length != ctx.paramNames.length then return false
    var i = 0; var rem = args
    while rem.nonEmpty do
      val ok = if ctx.paramIsRef(i) then walkRef(rem.head, ctx, mv)
               else if ctx.isDouble  then walkDouble(rem.head, ctx, mv)
               else                       walkLong(rem.head, ctx, mv)
      if !ok then return false
      i += 1; rem = rem.tail
    mv.visitMethodInsn(INVOKESTATIC, ctx.selfClass, ctx.staticMethodName,
      buildStaticDesc(ctx.paramIsRef, ctx.isDouble), false)
    true

  private def emitStaticFunction(
    cw:        ClassWriter,
    cname:     String,
    f:         Value.FunV,
    sig:       MethodSig,
    parentCtx: GenCtx
  ): Boolean =
    val paramSlots = new Array[Int](sig.paramNames.length)
    var nextSlot0  = 0
    var i = 0
    while i < sig.paramNames.length do
      paramSlots(i) = nextSlot0
      nextSlot0 += (if sig.paramIsRef(i) then 1 else 2)
      i += 1
    var nextLocal = nextSlot0
    val fnCtx = GenCtx(
      funName          = f.name,
      staticMethodName = sig.methodName,
      params           = f.params.toSet,
      paramNames       = sig.paramNames,
      paramSlots       = paramSlots,
      paramIsRef       = sig.paramIsRef,
      isDouble         = sig.isDouble,
      bindings         = Map.empty,
      interp           = parentCtx.interp,
      coEmit           = parentCtx.coEmit,
      selfClass        = cname,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s },
      paramTypes       = f.paramTypes.toArray
    )
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, sig.methodName,
      buildStaticDesc(sig.paramIsRef, sig.isDouble), null, null)
    mv.visitCode()
    val ok = emitFnBody(f, fnCtx, parentCtx.interp, mv, sig.isDouble)
    if !ok then return false
    try mv.visitMaxs(0, 0) catch case _: Throwable => return false
    mv.visitEnd()
    true

  private def ensureCoEmittedLong(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.refReturning.contains(fnName) then return null
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) then return null
    val paramIsRef = classifyParamRefs(fn)
    val isDouble   = bodyHasDoubleLit(fn.body)
    if isDouble then return null
    val sig = MethodSig(staticMethodName(fn.name), fn.params.toArray, paramIsRef, isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    // Use COMPUTE_MAXS (not COMPUTE_FRAMES) for the scratch validation to avoid
    // NPE from ASM's frame analysis on complex mutual-recursion bytecode patterns.
    // The main ClassWriter uses COMPUTE_FRAMES for full validation.
    val scratch = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    scratch.visit(V21, ACC_PUBLIC | ACC_FINAL,
      s"scalascript/interpreter/vm/jit/asm/AsmJitScratch$$${classCounter.incrementAndGet()}",
      null, "java/lang/Object", Array.empty)
    val ok =
      try
        val bodyOk = emitStaticFunction(scratch, ctx.selfClass, fn, sig, ctx)
        scratch.visitEnd()
        bodyOk
      catch case _: Throwable => false
    ctx.coEmit.emitting.remove(fnName)
    if !ok then
      ctx.coEmit.signatures.remove(fnName)
      return null
    ctx.coEmit.extraMethods.put(fnName, (cw: ClassWriter) => emitStaticFunction(cw, ctx.selfClass, fn, sig, ctx))
    ctx.coEmit.emitted.add(fnName)
    sig

  private def emitLongCall(fnName: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    val sig =
      if fnName == ctx.funName then ctx.coEmit.signatures.getOrElse(fnName, MethodSig(ctx.staticMethodName, ctx.paramNames, ctx.paramIsRef, ctx.isDouble))
      else ensureCoEmittedLong(fnName, ctx)
    if sig != null && !sig.isDouble && args.length == sig.paramNames.length then
      var i = 0
      var rem = args
      while rem.nonEmpty do
        val ok = if sig.paramIsRef(i) then walkRef(rem.head, ctx, mv)
                 else walkLong(rem.head, ctx, mv)
        if !ok then return false
        i += 1
        rem = rem.tail
      mv.visitMethodInsn(INVOKESTATIC, ctx.selfClass, sig.methodName,
        buildStaticDesc(sig.paramIsRef, sig.isDouble), false)
      return true
    // Co-emit failed. Fall back to JitGlobals.callGlobalLong1/2/3 if the callee
    // is a global FunV with all-Long params (non-HOF case).
    if fnName == ctx.funName then return false
    val n = args.length
    if n < 1 || n > 3 then return false
    ctx.interp.globals.getOrElse(fnName, null) match
      case fn: Value.FunV if fn.params.length == n && fn.usingParams.isEmpty =>
        val paramIsRef = classifyParamRefs(fn)
        if paramIsRef.exists(identity) then return false
        val jkgOwner = "scalascript/interpreter/vm/jit/JitGlobals$"
        mv.visitFieldInsn(GETSTATIC, jkgOwner, "MODULE$", s"L$jkgOwner;")
        mv.visitLdcInsn(fnName)
        var rem = args
        while rem.nonEmpty do
          if !walkLong(rem.head, ctx, mv) then return false
          rem = rem.tail
        val (desc, mname) =
          if n == 1 then ("(Ljava/lang/String;J)J", "callGlobalLong1")
          else if n == 2 then ("(Ljava/lang/String;JJ)J", "callGlobalLong2")
          else ("(Ljava/lang/String;JJJ)J", "callGlobalLong3")
        mv.visitMethodInsn(INVOKEVIRTUAL, jkgOwner, mname, desc, false)
        true
      case _ =>
        // HOF param call: `f(arg)` where `f` is a function-typed parameter.
        // Stack protocol: ALOAD slot, CHECKCAST LongFnN, emit arg(s), INVOKEINTERFACE.
        val paramIdx = ctx.paramNames.indexOf(fnName)
        if paramIdx < 0 || paramIdx >= ctx.paramTypes.length || !ctx.paramTypes(paramIdx).contains("=>") then
          return false
        val slot = ctx.slotOf(fnName)
        if slot < 0 then return false
        if n == 1 then
          mv.visitVarInsn(ALOAD, slot)
          mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/LongFn1")
          if !walkLong(args.head, ctx, mv) then return false
          mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/LongFn1",
            "apply", "(J)J", true)
          true
        else if n == 2 then
          mv.visitVarInsn(ALOAD, slot)
          mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/LongFn2")
          if !walkLong(args.head, ctx, mv) then return false
          if !walkLong(args(1), ctx, mv) then return false
          mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/LongFn2",
            "apply", "(JJ)J", true)
          true
        else if n == 3 then
          mv.visitVarInsn(ALOAD, slot)
          mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/LongFn3")
          if !walkLong(args.head, ctx, mv) then return false
          if !walkLong(args(1), ctx, mv) then return false
          if !walkLong(args(2), ctx, mv) then return false
          mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/LongFn3",
            "apply", "(JJJ)J", true)
          true
        else false

  private def emitStaticRefFunction(
    cw:        ClassWriter,
    cname:     String,
    f:         Value.FunV,
    sig:       MethodSig,
    parentCtx: GenCtx
  ): Boolean =
    val paramSlots = new Array[Int](sig.paramNames.length)
    var nextSlot0 = 0
    var i = 0
    while i < sig.paramNames.length do
      paramSlots(i) = nextSlot0
      nextSlot0 += (if sig.paramIsRef(i) then 1 else 2)
      i += 1
    var nextLocal = nextSlot0
    val fnCtx = GenCtx(
      funName          = f.name,
      staticMethodName = sig.methodName,
      params           = f.params.toSet,
      paramNames       = sig.paramNames,
      paramSlots       = paramSlots,
      paramIsRef       = sig.paramIsRef,
      isDouble         = false,
      bindings         = Map.empty,
      interp           = parentCtx.interp,
      coEmit           = parentCtx.coEmit,
      selfClass        = cname,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s },
      paramTypes       = f.paramTypes.toArray
    )
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, sig.methodName, buildRefDesc(sig.paramIsRef), null, null)
    mv.visitCode()
    val ok =
      if f.params.length == 1 && sig.paramIsRef(0) then
        f.body match
          case tm: Term.Match => emitRefMatchBody(tm, fnCtx, parentCtx.interp, mv)
          case _              => false
      else emitObject(f.body.asInstanceOf[Term], fnCtx, mv) && { mv.visitInsn(ARETURN); true }
    if !ok then return false
    try mv.visitMaxs(0, 0) catch case _: Throwable => return false
    mv.visitEnd()
    true

  private def ensureCoEmittedRef(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.refReturning.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return null
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) || bodyHasDoubleLit(fn.body) then return null
    val sig = MethodSig(staticMethodName(fn.name), fn.params.toArray, classifyParamRefs(fn), isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    val scratch = new ClassWriter(ClassWriter.COMPUTE_MAXS)
    scratch.visit(V21, ACC_PUBLIC | ACC_FINAL,
      s"scalascript/interpreter/vm/jit/asm/AsmJitRefScratch$$${classCounter.incrementAndGet()}",
      null, "java/lang/Object", Array.empty)
    val ok =
      try
        val bodyOk = emitStaticRefFunction(scratch, ctx.selfClass, fn, sig, ctx)
        scratch.visitEnd()
        bodyOk
      catch case _: Throwable => false
    ctx.coEmit.emitting.remove(fnName)
    if !ok then
      ctx.coEmit.signatures.remove(fnName)
      return null
    ctx.coEmit.extraMethods.put(fnName, (cw: ClassWriter) => emitStaticRefFunction(cw, ctx.selfClass, fn, sig, ctx))
    ctx.coEmit.emitted.add(fnName)
    ctx.coEmit.refReturning.add(fnName)
    sig

  private def emitRefCall(fnName: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    if fnName == ctx.funName then return false
    val sig = ensureCoEmittedRef(fnName, ctx)
    if sig == null || args.length != sig.paramNames.length then return false
    var i = 0
    var rem = args
    while rem.nonEmpty do
      val ok = if sig.paramIsRef(i) then walkRef(rem.head, ctx, mv) else walkLong(rem.head, ctx, mv)
      if !ok then return false
      i += 1
      rem = rem.tail
    mv.visitMethodInsn(INVOKESTATIC, ctx.selfClass, sig.methodName, buildRefDesc(sig.paramIsRef), false)
    true

  // ── walkString ────────────────────────────────────────────────────────────

  /** String-typed parallel of walkLong: leaves a `java/lang/String` ref on the
   *  stack. Supports literals, `+` concat (numeric operands coerced), String
   *  params, and calls to String-returning siblings. Returns false (caller
   *  bails) for any other shape. */
  private def walkString(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Lit.String(v) => mv.visitLdcInsn(v); true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkString(inner, ctx, mv)
        case _           => false
    case tn: Term.Name if ctx.isStringName(tn.value) =>
      val s = ctx.slotOf(tn.value); if s < 0 then false else { mv.visitVarInsn(ALOAD, s); true }
    case ApplyInfixPlus(lhs, rhs) if isStringExpr(lhs, ctx) || isStringExpr(rhs, ctx) =>
      mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
      mv.visitInsn(DUP)
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
      if !emitStringAppend(t, ctx, mv) then false
      else
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)
        true
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name => emitStringCall(fn.value, ap.argClause.values, ctx, mv)
        case _             => false
    case _ => false

  /** Pure predicate: does `t` denote a String expression? Used to pick the
   *  StringBuilder.append overload. For calls this may co-emit the sibling
   *  (idempotent) to learn its return type. */
  private def isStringExpr(t: Term, ctx: GenCtx): Boolean = t match
    case _: Lit.String => true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match { case inner: Term => isStringExpr(inner, ctx); case _ => false }
    case ApplyInfixPlus(lhs, rhs) => isStringExpr(lhs, ctx) || isStringExpr(rhs, ctx)
    case tn: Term.Name => ctx.isStringName(tn.value)
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name => ensureCoEmittedString(fn.value, ctx) != null
        case _             => false
    case _ => false

  /** Append `t` to the StringBuilder already on the stack, leaving the
   *  StringBuilder. A `+` chain is flattened into successive appends; leaves
   *  are appended via the String or long overload per `isStringExpr`. */
  private def emitStringAppend(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case ApplyInfixPlus(lhs, rhs) if isStringExpr(lhs, ctx) || isStringExpr(rhs, ctx) =>
      emitStringAppend(lhs, ctx, mv) && emitStringAppend(rhs, ctx, mv)
    case _ =>
      if isStringExpr(t, ctx) then
        if !walkString(t, ctx, mv) then false
        else
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
          true
      else
        if !walkLong(t, ctx, mv) then false
        else
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(J)Ljava/lang/StringBuilder;", false)
          true

  /** Descriptor for a String-returning static method (params are long/ref). */
  private def buildStringDesc(paramIsRef: Array[Boolean]): String =
    val sb = new StringBuilder("(")
    for r <- paramIsRef do
      if r then sb.append("Ljava/lang/Object;") else sb.append("J")
    sb.append(")Ljava/lang/String;")
    sb.toString

  private def emitStaticStringFunction(
    cw:        ClassWriter,
    cname:     String,
    f:         Value.FunV,
    sig:       MethodSig,
    parentCtx: GenCtx
  ): Boolean =
    val paramSlots = new Array[Int](sig.paramNames.length)
    var nextSlot0  = 0
    var i = 0
    while i < sig.paramNames.length do
      paramSlots(i) = nextSlot0; nextSlot0 += (if sig.paramIsRef(i) then 1 else 2); i += 1
    var nextLocal = nextSlot0
    val fnCtx = GenCtx(
      funName          = f.name,
      staticMethodName = sig.methodName,
      params           = f.params.toSet,
      paramNames       = sig.paramNames,
      paramSlots       = paramSlots,
      paramIsRef       = sig.paramIsRef,
      isDouble         = false,
      bindings         = Map.empty,
      interp           = parentCtx.interp,
      coEmit           = parentCtx.coEmit,
      selfClass        = cname,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s },
      paramTypes       = f.paramTypes.toArray
    )
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, sig.methodName,
      buildStringDesc(sig.paramIsRef), null, null)
    mv.visitCode()
    if !walkString(f.body.asInstanceOf[Term], fnCtx, mv) then return false
    mv.visitInsn(ARETURN)
    try mv.visitMaxs(0, 0) catch case _: Throwable => return false
    mv.visitEnd()
    true

  private def ensureCoEmittedString(fnName: String, ctx: GenCtx): MethodSig | Null =
    if ctx.coEmit.stringReturning.contains(fnName) then
      return ctx.coEmit.signatures.getOrElse(fnName, null)
    if ctx.coEmit.emitted.contains(fnName) || ctx.coEmit.emitting.contains(fnName) then
      return null
    val fnV = ctx.interp.globals.getOrElse(fnName, null)
    if !fnV.isInstanceOf[Value.FunV] then return null
    val fn = fnV.asInstanceOf[Value.FunV]
    if !jitCompatibleSibling(fn) then return null
    if bodyHasDoubleLit(fn.body) then return null
    val sig = MethodSig(staticMethodName(fn.name), fn.params.toArray, classifyParamRefs(fn), isDouble = false)
    ctx.coEmit.signatures.put(fnName, sig)
    ctx.coEmit.emitting.add(fnName)
    val scratch = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    scratch.visit(V21, ACC_PUBLIC | ACC_FINAL,
      s"scalascript/interpreter/vm/jit/asm/AsmJitScratch$$${classCounter.incrementAndGet()}",
      null, "java/lang/Object", Array.empty)
    val ok =
      try
        val bodyOk = emitStaticStringFunction(scratch, ctx.selfClass, fn, sig, ctx)
        scratch.visitEnd()
        if bodyOk then scratch.toByteArray; bodyOk
      catch case _: Throwable => false
    ctx.coEmit.emitting.remove(fnName)
    if !ok then
      ctx.coEmit.signatures.remove(fnName)
      return null
    ctx.coEmit.extraMethods.put(fnName, (cw: ClassWriter) => emitStaticStringFunction(cw, ctx.selfClass, fn, sig, ctx))
    ctx.coEmit.emitted.add(fnName)
    ctx.coEmit.stringReturning.add(fnName)
    sig

  private def emitStringCall(fnName: String, args: List[Term], ctx: GenCtx, mv: MethodVisitor): Boolean =
    val sig = ensureCoEmittedString(fnName, ctx)
    if sig == null || args.length != sig.paramNames.length then return false
    var i = 0
    var rem = args
    while rem.nonEmpty do
      val ok = if sig.paramIsRef(i) then walkRef(rem.head, ctx, mv)
               else walkLong(rem.head, ctx, mv)
      if !ok then return false
      i += 1
      rem = rem.tail
    mv.visitMethodInsn(INVOKESTATIC, ctx.selfClass, sig.methodName,
      buildStringDesc(sig.paramIsRef), false)
    true

  /** Extractor for a binary `+` ApplyInfix (single arg), shared by walkString /
   *  isStringExpr / emitStringAppend. */
  private object ApplyInfixPlus:
    def unapply(t: Term): Option[(Term, Term)] = t match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac)
          if op.value == "+" && ac.values.lengthCompare(1) == 0 =>
        Some((lhs, ac.values.head))
      case _ => None

  private def callParamIsRef(fnName: String, argIdx: Int, ctx: GenCtx): Boolean =
    val sig =
      if fnName == ctx.funName then ctx.coEmit.signatures.getOrElse(fnName, MethodSig(ctx.staticMethodName, ctx.paramNames, ctx.paramIsRef, ctx.isDouble))
      else
        ctx.coEmit.signatures.get(fnName) match
          case Some(s) => s
          case None =>
            ctx.interp.globals.getOrElse(fnName, null) match
              case fn: Value.FunV if jitCompatibleSibling(fn) =>
                MethodSig(staticMethodName(fn.name), fn.params.toArray, classifyParamRefs(fn), bodyHasDoubleLit(fn.body))
              case _ => null
    sig != null && argIdx >= 0 && argIdx < sig.paramIsRef.length && sig.paramIsRef(argIdx)

  // ── walkRef ───────────────────────────────────────────────────────────────

  private def walkRef(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Term.Name("None") =>
      mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
      mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "NoneV", s"()L$optionVInt;", false)
      true
    // Stage 8: `xs ++ ys` — List/Map concat via collectionConcat (mirrors Javac).
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if op.value == "++" && argClause.values.lengthCompare(1) == 0 =>
      mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
      if !walkRef(lhs, ctx, mv) then return false
      if !walkRef(argClause.values.head, ctx, mv) then return false
      mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, "collectionConcat",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
      true
    // Stage 8: BigInt/Decimal infix arithmetic via JitRefDispatch (mirrors Javac).
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
        case _ => null
      if helper == null then false
      else
        mv.visitFieldInsn(GETSTATIC, refDispatchInt, "MODULE$", s"L$refDispatchInt;")
        if !emitNumericObjectValue(lhs, ctx, mv) then return false
        if !emitValueObject(argClause.values.head, ctx, mv) then return false
        mv.visitMethodInsn(INVOKEVIRTUAL, refDispatchInt, helper,
          s"(L$valueInt;L$valueInt;)L$valueInt;", false)
        true
    // Stage 8: s"prefix${arg1}mid${arg2}suffix" — emit via StringBuilder, wrap in StringV.
    // Numeric args use append(J); ref args go through Value.show then append(String).
    // Stage 8: `ref + x` String concat — mirror Javac apply-infix-ref subset.
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if op.value == "+" && argClause.values.lengthCompare(1) == 0 =>
      mv.visitTypeInsn(NEW, stringVInt)
      mv.visitInsn(DUP)
      mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
      mv.visitInsn(DUP)
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
      // append Value.show(lhsRef)
      if !walkRef(lhs, ctx, mv) then return false
      mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
      mv.visitInsn(SWAP)
      mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "show",
        s"(L$valueInt;)Ljava/lang/String;", false)
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
      // append rhs — try Long, else ref through show.
      val rhs = argClause.values.head
      if walkLong(rhs, ctx, mv) then
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
          "(J)Ljava/lang/StringBuilder;", false)
      else
        if !walkRef(rhs, ctx, mv) then return false
        mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
        mv.visitInsn(SWAP)
        mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "show",
          s"(L$valueInt;)Ljava/lang/String;", false)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
          "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
        "()Ljava/lang/String;", false)
      mv.visitMethodInsn(INVOKESPECIAL, stringVInt, "<init>", "(Ljava/lang/String;)V", false)
      true
    case Term.Interpolate(Term.Name("s"), parts, args)
        if parts.lengthCompare(args.length + 1) == 0
        && parts.forall(_.isInstanceOf[Lit.String]) =>
      val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
      mv.visitTypeInsn(NEW, stringVInt)
      mv.visitInsn(DUP)
      mv.visitTypeInsn(NEW, "java/lang/StringBuilder")
      mv.visitInsn(DUP)
      mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false)
      var idx = 0
      while idx < partStrs.length do
        if partStrs(idx).nonEmpty then
          mv.visitLdcInsn(partStrs(idx))
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        if idx < args.length then
          val arg = args(idx).asInstanceOf[Term]
          if walkLong(arg, ctx, mv) then
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
              "(J)Ljava/lang/StringBuilder;", false)
          else
            if !walkRef(arg, ctx, mv) then return false
            mv.visitFieldInsn(GETSTATIC, valueModuleInt, "MODULE$", s"L$valueModuleInt;")
            mv.visitInsn(SWAP)
            mv.visitMethodInsn(INVOKEVIRTUAL, valueModuleInt, "show",
              s"(L$valueInt;)Ljava/lang/String;", false)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
              "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)
        idx += 1
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
        "()Ljava/lang/String;", false)
      mv.visitMethodInsn(INVOKESPECIAL, stringVInt, "<init>", "(Ljava/lang/String;)V", false)
      true
    case tn: Term.Name if ctx.isRefName(tn.value) =>
      val s = ctx.slotOf(tn.value); if s < 0 then false else { mv.visitVarInsn(ALOAD, s); true }
    case tn: Term.Name =>
      ctx.interp.globals.getOrElse(tn.value, null) match
        case _: Value.ListV | _: Value.OptionV | _: Value.InstanceV | _: Value.MapV | _: Value.StringV =>
          emitGlobalRef(tn.value, mv)
          true
        case _ => false
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkRef(inner, ctx, mv)
        case _           => false
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if op.value == "until" && argClause.values.lengthCompare(1) == 0 =>
      mv.visitFieldInsn(GETSTATIC, hofDispatchInt, "MODULE$", s"L$hofDispatchInt;")
      if !walkLong(lhs, ctx, mv) then return false
      if !walkLong(argClause.values.head, ctx, mv) then return false
      mv.visitMethodInsn(INVOKEVIRTUAL, hofDispatchInt, "rangeUntil", "(JJ)Ljava/lang/Object;", false)
      true
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == "Some" && ap.argClause.values.lengthCompare(1) == 0 =>
          mv.visitTypeInsn(NEW, optionVInt)
          mv.visitInsn(DUP)
          if !emitValueObject(ap.argClause.values.head, ctx, mv) then return false
          mv.visitMethodInsn(INVOKESPECIAL, optionVInt, "<init>", s"(L$valueInt;)V", false)
          true
        case fn: Term.Name if (fn.value == "Right" || fn.value == "Left") && ap.argClause.values.lengthCompare(1) == 0 =>
          emitBuiltinEitherObject(fn.value, ap.argClause.values.head, ctx, mv)
        case fn: Term.Name =>
          emitRefCall(fn.value, ap.argClause.values, ctx, mv)
        case Term.Select(recv: Term, Term.Name(method)) =>
          emitHofRefChain(recv, method, ap.argClause.values, ctx, mv) ||
            emitRefChainObject(recv, method, ap.argClause.values, ctx, mv)
        case _ => false
    // Stage 5.5: ref-typed ADT field access `obj.field` on a ref param.
    case Term.Select(Term.Name(objName), Term.Name(field)) if ctx.isRefName(objName) =>
      val tn = ctx.refTypeOf(objName)
      if tn == null then return false
      val fo = ctx.interp.typeFieldOrder.getOrElse(tn, Nil)
      val idx = fo.indexOf(field)
      if idx < 0 then return false
      val ft = ctx.interp.typeFieldTypes.getOrElse(tn, Nil)
      val ftype = if idx < ft.length then ft(idx) else ""
      if ftype == "Int" || ftype == "Long" || ftype == "Double" then return false
      val slot = ctx.slotOf(objName)
      if slot < 0 then return false
      val iSlot = ctx.allocSlot()
      mv.visitVarInsn(ALOAD, slot)
      mv.visitTypeInsn(CHECKCAST, instVInt)
      mv.visitVarInsn(ASTORE, iSlot)
      val faSlot = ctx.allocSlot()
      mv.visitVarInsn(ALOAD, iSlot)
      mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
      mv.visitVarInsn(ASTORE, faSlot)
      emitLoadField(mv, iSlot, faSlot, idx, field, hasArr = true)
      true
    case _ => false

  // ── walkBool: emit jump-to-ifFalse when condition is false ───────────────

  /** True when walkBool(t, ...) always emits an unconditional jump and never falls through.
   *  Used to suppress dead-code GOTO instructions that cause ASM visitMaxs NPE. */
  private def boolAlwaysJumps(t: Term): Boolean = t match
    case Lit.Boolean(false) => true   // emits GOTO ifFalse, no fall-through
    case Term.Block(List(inner: Term)) => boolAlwaysJumps(inner)
    case _ => false

  /** Stage 8: guard expression emission with Long-fallback (mirrors stage-6
   *  bool-body-ext for guards). Tries `walkBool` first; on failure, emits
   *  `walkLong` and `IFEQ ifFalse` (Long == 0 → false → jump). Returns true
   *  if some path emitted a Boolean test. */
  private def emitGuardBool(t: Term, ctx: GenCtx, mv: MethodVisitor, ifFalse: Label): Boolean =
    if walkBool(t, ctx, mv, ifFalse) then true
    else if walkLong(t, ctx, mv) then
      // walkLong leaves a long on the stack; compare to 0L and jump if equal (false).
      mv.visitInsn(LCONST_0)
      mv.visitInsn(LCMP)
      mv.visitJumpInsn(IFEQ, ifFalse)
      true
    else false

  private def walkBool(t: Term, ctx: GenCtx, mv: MethodVisitor, ifFalse: Label): Boolean =
    t match
      case Lit.Boolean(b) =>
        if !b then mv.visitJumpInsn(GOTO, ifFalse)
        true
      case Term.ApplyUnary(op, arg) if op.value == "!" =>
        val Ltrue = new Label
        if !walkBool(arg, ctx, mv, Ltrue) then return false
        mv.visitJumpInsn(GOTO, ifFalse)
        mv.visitLabel(Ltrue)
        true
      case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
        b.stats.head match
          case inner: Term => walkBool(inner, ctx, mv, ifFalse)
          case _           => false
      case ti: Term.If =>
        val Lelse = new Label
        if !walkBool(ti.cond, ctx, mv, Lelse) then return false
        if !walkBool(ti.thenp, ctx, mv, ifFalse) then return false
        // Skip GOTO Lend when thenp always jumps unconditionally (e.g. Lit.Boolean(false)
        // emits an unconditional GOTO, so the following GOTO Lend would be dead code and
        // cause visitMaxs / COMPUTE_FRAMES to throw NPE on the unreachable branch target).
        val thenAlwaysJumps = boolAlwaysJumps(ti.thenp)
        val Lend = if !thenAlwaysJumps then { val l = new Label; mv.visitJumpInsn(GOTO, l); l } else null
        mv.visitLabel(Lelse)
        if !walkBool(ti.elsep, ctx, mv, ifFalse) then return false
        if Lend != null then mv.visitLabel(Lend)
        true
      case tn: Term.Name if !ctx.isRefName(tn.value) && ctx.isParam(tn.value) =>
        // Bool-typed local/param encoded as long 0/1: non-zero = true.
        if !walkLong(tn, ctx, mv) then return false
        mv.visitInsn(LCONST_0); mv.visitInsn(LCMP)
        mv.visitJumpInsn(IFEQ, ifFalse)
        true
      case ap: Term.Apply =>
        ap.fun match
          case fn: Term.Name =>
            if !emitLongCall(fn.value, ap.argClause.values, ctx, mv) then return false
            mv.visitInsn(LCONST_0); mv.visitInsn(LCMP)
            mv.visitJumpInsn(IFEQ, ifFalse)
            true
          case _ => false
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac) if ac.values.lengthCompare(1) == 0 =>
        op.value match
          case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
            val w = if ctx.isDouble then walkDouble else walkLong
            w(lhs, ctx, mv) && w(ac.values.head, ctx, mv) && {
              if ctx.isDouble then mv.visitInsn(DCMPG)
              else mv.visitInsn(LCMP)
              mv.visitJumpInsn(invertCmp(op.value), ifFalse); true }
          case "&&" =>
            walkBool(lhs, ctx, mv, ifFalse) && walkBool(ac.values.head, ctx, mv, ifFalse)
          case "||" =>
            val Lskip = new Label; val Ltmp = new Label
            if !walkBool(lhs, ctx, mv, Ltmp) then return false
            mv.visitJumpInsn(GOTO, Lskip)
            mv.visitLabel(Ltmp)
            if !walkBool(ac.values.head, ctx, mv, ifFalse) then return false
            mv.visitLabel(Lskip); true
          case _ => false
      case other =>
        // General fallback: any Long-compilable expression where non-zero = true.
        // Handles bool-returning match expressions, Lit.Boolean (via walkLong),
        // and other Long-valued sub-expressions used in boolean position.
        if !walkLong(other, ctx, mv) then return false
        mv.visitInsn(LCONST_0); mv.visitInsn(LCMP)
        mv.visitJumpInsn(IFEQ, ifFalse); true

  private def invertCmp(op: String): Int = op match
    case "<"  => IFGE; case "<=" => IFGT; case ">"  => IFLE
    case ">=" => IFLT; case "==" => IFNE; case "!=" => IFEQ; case _ => IFNE

  private def longArith(op: String): Int = op match
    case "+" => LADD; case "-" => LSUB; case "*" => LMUL
    case "/" => LDIV; case "%" => LREM; case _ => LADD

  private def dblArith(op: String): Int = op match
    case "+" => DADD; case "-" => DSUB; case "*" => DMUL
    case "/" => DDIV; case "%" => DREM; case _ => DADD

  private def cmpJump(op: String): Int = op match
    case "<"  => IFLT; case "<=" => IFLE; case ">"  => IFGT
    case ">=" => IFGE; case "==" => IFEQ; case "!=" => IFNE; case _ => IFEQ

  private def isCatchAllPat(p: scala.meta.Pat): Boolean =
    p.isInstanceOf[Pat.Wildcard] || p.isInstanceOf[Pat.Var]

  /** True when every arm is a `Pat.Lit(Int/Long)`, `Pat.Var`, or `Pat.Wildcard`
   *  with no guards — the match compiles as a long-compare if-chain. */
  private def isLiteralIntMatch(tm: Term.Match): Boolean =
    tm.casesBlock.cases.forall { c =>
      c.cond.isEmpty && (c.pat match
        case _: Pat.Wildcard | _: Pat.Var      => true
        case _: Lit.Int | _: Lit.Long => true
        case _                                  => false
      )
    }

  /** Emit a literal-integer match as a compare-and-jump chain.
   *  `returnForm = true` → each arm emits LRETURN; `false` → arm value left on
   *  stack and execution falls through to `endLbl` (expression context). */
  private def emitLiteralIntMatch(
    tm:         Term.Match,
    ctx:        GenCtx,
    mv:         MethodVisitor,
    returnForm: Boolean,
    endLbl:     Label | Null
  ): Boolean =
    // Evaluate scrutinee and store in a fresh long slot.
    if !walkLong(tm.expr.asInstanceOf[Term], ctx, mv) then return false
    val scrSlot = ctx.allocSlot()
    mv.visitVarInsn(LSTORE, scrSlot)
    val cases  = tm.casesBlock.cases.toList
    val Lend   = if returnForm then null else endLbl
    var armRem = cases
    while armRem.nonEmpty do
      val arm = armRem.head; armRem = armRem.tail
      arm.pat match
        case _: Pat.Wildcard | _: Pat.Var =>
          if !walkLong(arm.body.asInstanceOf[Term], ctx, mv) then return false
          if returnForm then mv.visitInsn(LRETURN)
          else if Lend != null then mv.visitJumpInsn(GOTO, Lend)
        case li: Lit =>
          val litVal: Long = li match
            case Lit.Int(v)  => v.toLong
            case Lit.Long(v) => v
            case _           => return false
          val Lnext = new Label
          mv.visitVarInsn(LLOAD, scrSlot)
          mv.visitLdcInsn(litVal)
          mv.visitInsn(LCMP)
          mv.visitJumpInsn(IFNE, Lnext)
          if !walkLong(arm.body.asInstanceOf[Term], ctx, mv) then return false
          if returnForm then mv.visitInsn(LRETURN)
          else if Lend != null then mv.visitJumpInsn(GOTO, Lend)
          mv.visitLabel(Lnext)
        case _ => return false
    val lastPat = cases.last.pat
    if !isCatchAllPat(lastPat) then
      emitThrow(mv, "AsmJitBackend: literal match: no arm matched")
    true

  // ── Tuple match body ─────────────────────────────────────────────────────

  private def isTupleMatch(tm: Term.Match): Boolean =
    val cases = tm.casesBlock.cases
    cases.nonEmpty &&
    cases.exists(_.pat.isInstanceOf[Pat.Tuple]) &&
    cases.forall { c =>
      c.pat match
        case _: Pat.Tuple    => true
        case _: Pat.Wildcard => true
        case _: Pat.Var      => true
        case _               => false
    }

  /** Push the i-th element of a scala.collection.immutable.List stored in `elemsSlot`.
   *  Result is an Object on the stack. */
  private def emitListApply(elemsSlot: Int, i: Int, mv: MethodVisitor): Unit =
    mv.visitVarInsn(ALOAD, elemsSlot)
    if i <= 5 then mv.visitInsn(ICONST_0 + i)
    else if i <= 127 then mv.visitIntInsn(BIPUSH, i)
    else mv.visitIntInsn(SIPUSH, i)
    mv.visitMethodInsn(INVOKEVIRTUAL, scalaList, "apply", "(I)Ljava/lang/Object;", false)

  /** Emit body-return form for a match where all arms are Pat.Tuple/Wildcard/Var. */
  private def emitTupleMatchBody(tm: Term.Match, ctx: GenCtx, interp: Interpreter,
                                 mv: MethodVisitor, isDouble: Boolean): Boolean =
    tm.expr match
      case n: Term.Name =>
        if !ctx.params.contains(n.value) then return false
        val s = ctx.slotOf(n.value)
        if s < 0 then return false
        mv.visitVarInsn(ALOAD, s)
      case other =>
        if !walkRef(other, ctx, mv) then return false
    mv.visitTypeInsn(CHECKCAST, tupleVInt)
    mv.visitMethodInsn(INVOKEVIRTUAL, tupleVInt, "elems", "()Lscala/collection/immutable/List;", false)
    val elemsSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, elemsSlot)

    val cases      = tm.casesBlock.cases
    val casesArr   = cases.toArray
    val multiTuple = casesArr.count(_.pat.isInstanceOf[Pat.Tuple]) > 1

    var rem = cases
    while rem.nonEmpty do
      val c = rem.head; rem = rem.tail
      c.pat match
        case pt: Pat.Tuple =>
          val patsArr = pt.args.toArray
          val n = patsArr.length
          val skipLbl = if multiTuple then
            mv.visitVarInsn(ALOAD, elemsSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, scalaList, "length", "()I", false)
            if n <= 5 then mv.visitInsn(ICONST_0 + n)
            else if n <= 127 then mv.visitIntInsn(BIPUSH, n)
            else mv.visitIntInsn(SIPUSH, n)
            val lbl = new Label
            mv.visitJumpInsn(IF_ICMPNE, lbl)
            lbl
          else null

          val bindNames = new Array[String](n)
          var i = 0; var ok = true
          while i < n && ok do
            patsArr(i) match
              case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
              case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
              case _                      => ok = false
            i += 1
          if !ok then return false

          val bindSlots = new Array[Int](n)
          val bindIsRef = new Array[Boolean](n)
          val newBindings = scala.collection.mutable.Map.empty[String, (Int, Boolean)]
          i = 0
          while i < n do
            if !bindNames(i).startsWith("_unused$") then
              val isRef = bindingIsRef(c.body, bindNames(i), ctx)
              val s = ctx.allocSlot()
              bindSlots(i) = s
              bindIsRef(i) = isRef
              newBindings += (bindNames(i) -> (s, isRef))
            i += 1
          val newCtx = ctx.withBindings(newBindings.toMap)

          i = 0
          while i < n do
            if !bindNames(i).startsWith("_unused$") then
              emitListApply(elemsSlot, i, mv)
              if bindIsRef(i) then
                mv.visitVarInsn(ASTORE, bindSlots(i))
              else if isDouble then
                val raw = ctx.allocSlot()
                mv.visitVarInsn(ASTORE, raw)
                val isDoubleLabel = new Label; val endLbl2 = new Label
                mv.visitVarInsn(ALOAD, raw)
                mv.visitTypeInsn(INSTANCEOF, dblVInt)
                mv.visitJumpInsn(IFEQ, isDoubleLabel)
                mv.visitVarInsn(ALOAD, raw)
                mv.visitTypeInsn(CHECKCAST, dblVInt)
                mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
                mv.visitJumpInsn(GOTO, endLbl2)
                mv.visitLabel(isDoubleLabel)
                mv.visitVarInsn(ALOAD, raw)
                mv.visitTypeInsn(CHECKCAST, intVInt)
                mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
                mv.visitInsn(L2D)
                mv.visitLabel(endLbl2)
                mv.visitVarInsn(DSTORE, bindSlots(i))
              else
                mv.visitTypeInsn(CHECKCAST, intVInt)
                mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
                mv.visitVarInsn(LSTORE, bindSlots(i))
            i += 1

          if c.cond.nonEmpty then
            val skipBody = new Label
            if !emitGuardBool(c.cond.get, newCtx, mv, skipBody) then return false
            val ok2 = if isDouble then walkDouble(c.body, newCtx, mv) && { mv.visitInsn(DRETURN); true }
                      else walkLong(c.body, newCtx, mv) && { mv.visitInsn(LRETURN); true }
            if !ok2 then return false
            mv.visitLabel(skipBody)
          else
            val ok2 = if isDouble then walkDouble(c.body, newCtx, mv) && { mv.visitInsn(DRETURN); true }
                      else walkLong(c.body, newCtx, mv) && { mv.visitInsn(LRETURN); true }
            if !ok2 then return false

          if multiTuple then mv.visitLabel(skipLbl)

        case _: Pat.Wildcard =>
          val ok2 = if isDouble then walkDouble(c.body, ctx, mv) && { mv.visitInsn(DRETURN); true }
                    else walkLong(c.body, ctx, mv) && { mv.visitInsn(LRETURN); true }
          if !ok2 then return false
        case pv: Pat.Var =>
          val xSlot = ctx.allocSlot()
          tm.expr match
            case n: Term.Name =>
              val s = ctx.slotOf(n.value)
              if s < 0 then return false
              mv.visitVarInsn(ALOAD, s)
            case _ => return false
          mv.visitVarInsn(ASTORE, xSlot)
          val newCtx = ctx.withBindings(Map(pv.name.value -> (xSlot, true)))
          val ok2 = if isDouble then walkDouble(c.body, newCtx, mv) && { mv.visitInsn(DRETURN); true }
                    else walkLong(c.body, newCtx, mv) && { mv.visitInsn(LRETURN); true }
          if !ok2 then return false
        case _ => return false

    if !cases.exists(c => c.pat.isInstanceOf[Pat.Wildcard] || c.pat.isInstanceOf[Pat.Var]) then
      emitThrow(mv, "AsmJitBackend: tuple match no case matched")
    true

  // ── Match body (top-level: emits RETURN inside each arm) ─────────────────

  private def emitMatchBody(tm: Term.Match, ctx: GenCtx, interp: Interpreter,
                            mv: MethodVisitor): Boolean =
    // Stage 5.5: support non-Term.Name scrutinees (e.g. field access) by hoisting.
    tm.expr match
      case n: Term.Name =>
        if !ctx.params.contains(n.value) then return false
        val s = ctx.slotOf(n.value)
        if s < 0 then return false
        mv.visitVarInsn(ALOAD, s)
      case other =>
        if !walkRef(other, ctx, mv) then return false
    mv.visitTypeInsn(CHECKCAST, instVInt)
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, instSlot)

    val cases    = tm.casesBlock.cases
    val casesArr = cases.toArray
    val tags     = resolveArmTags(casesArr, interp)
    val wildcardIdx = casesArr.indexWhere(c => isCatchAllPat(c.pat))
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
    val armLbls  = Array.fill(casesArr.length)(new Label)
    val throwLbl = new Label
    val defLbl   = if wildcardIdx >= 0 then armLbls(wildcardIdx) else throwLbl

    val hasAltPat = casesArr.exists(_.pat.isInstanceOf[Pat.Alternative])
    if casesArr.exists(_.cond.nonEmpty) || hasAltPat then
      var ci = 0
      var rem = cases
      while rem.nonEmpty do
        if !emitArmAsIfBranch(rem.head, ctx, interp, instSlot, mv, if allTagged then tags(ci) else 0, allTagged) then return false
        ci += 1
        rem = rem.tail
      if wildcardIdx < 0 then
        mv.visitLabel(throwLbl)
        emitThrow(mv, "AsmJitBackend: no guarded case matched")
      return true
    else if allTagged then
      mv.visitVarInsn(ALOAD, instSlot)
      mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
      emitSwitch(mv, tags, armLbls, defLbl)
    else
      mv.visitVarInsn(ALOAD, instSlot)
      mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
      emitStringChain(mv, cases, armLbls, defLbl)

    var ci = 0; var rem = cases
    while rem.nonEmpty do
      mv.visitLabel(armLbls(ci))
      if !emitArmBody(rem.head, ctx, interp, instSlot, mv, exprForm = false, null) then return false
      ci += 1; rem = rem.tail

    if wildcardIdx < 0 then
      mv.visitLabel(throwLbl)
      emitThrow(mv, "AsmJitBackend: no case matched")
    true

  // ── Match expression (nested match: leaves value on stack) ───────────────

  private def walkMatchExpr(tm: Term.Match, ctx: GenCtx, mv: MethodVisitor): Boolean =
    if isTupleMatch(tm) then
      // Wrap in a lambda IIFE: LongSupplier / DoubleSupplier.
      // Reuse emitTupleMatchBody by emitting an inner method via a lambda capture.
      // Simpler: emit as a LongSupplier inline lambda same as JavacJitBackend does.
      // We can't easily do this in ASM without full lambda mechanics, so bail here
      // and let JavacJitBackend handle the expression-position tuple match.
      return false
    if isLiteralIntMatch(tm) then
      val endLbl = new Label
      val ok = emitLiteralIntMatch(tm, ctx, mv, returnForm = false, endLbl = endLbl)
      if ok then mv.visitLabel(endLbl)
      return ok
    val scrutName = tm.expr match
      case n: Term.Name => n.value; case _ => return false
    if !ctx.params.contains(scrutName) then return false
    val scrutSlot = ctx.slotOf(scrutName)
    if scrutSlot < 0 then return false

    mv.visitVarInsn(ALOAD, scrutSlot)
    mv.visitTypeInsn(CHECKCAST, instVInt)
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, instSlot)

    val cases    = tm.casesBlock.cases
    val casesArr = cases.toArray
    val wildcardIdx = casesArr.indexWhere(c => isCatchAllPat(c.pat))
    // Wildcard/Pat.Var must be last arm (catch-all).
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false
    val tags      = resolveArmTags(casesArr, ctx.interp)
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
    val hasAnyGuard = casesArr.exists(_.cond.nonEmpty)
    val endLbl    = new Label

    if hasAnyGuard then
      // Guard path: if-chain (same as emitMatchBody's guard path but in expr form:
      // emitArmBodyGuarded uses GOTO endLbl instead of LRETURN when guard passes).
      var ci = 0; var rem = cases
      while rem.nonEmpty do
        if !emitArmBodyGuarded(rem.head, ctx, ctx.interp, instSlot, mv,
                               if allTagged then tags(ci) else 0, allTagged, endLbl) then return false
        ci += 1; rem = rem.tail
      if wildcardIdx < 0 then
        emitThrow(mv, "AsmJitBackend: no guarded case matched")
      mv.visitLabel(endLbl); true
    else
      val armLbls  = Array.fill(casesArr.length)(new Label)
      val throwLbl = new Label
      val defLbl   = if wildcardIdx >= 0 then armLbls(wildcardIdx) else throwLbl

      if allTagged then
        mv.visitVarInsn(ALOAD, instSlot)
        mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
        emitSwitch(mv, tags, armLbls, defLbl)
      else
        mv.visitVarInsn(ALOAD, instSlot)
        mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
        emitStringChain(mv, cases, armLbls, defLbl)

      var ci = 0; var rem = cases
      while rem.nonEmpty do
        mv.visitLabel(armLbls(ci))
        if !emitArmBody(rem.head, ctx, ctx.interp, instSlot, mv, exprForm = true, endLbl) then return false
        ci += 1; rem = rem.tail

      if wildcardIdx < 0 then
        mv.visitLabel(throwLbl)
        emitThrow(mv, "AsmJitBackend: no case matched")
      mv.visitLabel(endLbl); true

  // ── Single arm emission ───────────────────────────────────────────────────

  private def emitArmBody(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                          instSlot: Int, mv: MethodVisitor,
                          exprForm: Boolean, endLbl: Label | Null): Boolean =
    if c.cond.nonEmpty then return false
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = extractCtorName(ext.fun)
        if ctorName == null then return false
        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
            case _                      => return false
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return false

        val bindIsRef = Array.tabulate(n) { bi =>
          !bindNames(bi).startsWith("_unused$") && bindingIsRef(c.body, bindNames(bi), ctx)
        }

        // Hoist fieldsArr lookup.
        val faSlot =
          if n > 0 then
            val s = ctx.allocSlot()
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
            mv.visitVarInsn(ASTORE, s); s
          else -1

        val newBindings = mutable.Map.empty[String, (Int, Boolean)]
        i = 0
        while i < n do
          if !bindNames(i).startsWith("_unused$") then
            val fname = fieldOrder(i)
            val isRef = bindIsRef(i)
            val bSlot = ctx.allocSlot()
            emitLoadField(mv, instSlot, faSlot, i, fname, n > 0)
            if isRef then
              mv.visitVarInsn(ASTORE, bSlot)
            else if ctx.isDouble then
              val rawSlot = ctx.allocSlot()
              mv.visitVarInsn(ASTORE, rawSlot)
              val LisD = new Label; val Lm = new Label
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(INSTANCEOF, dblVInt)
              mv.visitJumpInsn(IFNE, LisD)
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitInsn(L2D); mv.visitJumpInsn(GOTO, Lm)
              mv.visitLabel(LisD)
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(CHECKCAST, dblVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
              mv.visitLabel(Lm)
              mv.visitVarInsn(DSTORE, bSlot)
            else
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitVarInsn(LSTORE, bSlot)
            newBindings(bindNames(i)) = (bSlot, isRef)
          i += 1

        val newCtx = ctx.withBindings(newBindings.toMap)
        val bodyOk =
          if ctx.isDouble then walkDouble(c.body, newCtx, mv)
          else walkLong(c.body, newCtx, mv)
        if !bodyOk then return false
        if exprForm then mv.visitJumpInsn(GOTO, endLbl.asInstanceOf[Label])
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        true
      case _: Pat.Wildcard =>
        val bodyOk = if ctx.isDouble then walkDouble(c.body, ctx, mv) else walkLong(c.body, ctx, mv)
        if !bodyOk then return false
        if exprForm then mv.visitJumpInsn(GOTO, endLbl.asInstanceOf[Label])
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        true
      case pv: Pat.Var =>
        val xName = pv.name.value
        val xSlot = ctx.allocSlot()
        mv.visitVarInsn(ALOAD, instSlot)
        mv.visitVarInsn(ASTORE, xSlot)
        val newCtx = ctx.withBindings(Map(xName -> (xSlot, true)))
        val bodyOk = if ctx.isDouble then walkDouble(c.body, newCtx, mv) else walkLong(c.body, newCtx, mv)
        if !bodyOk then return false
        if exprForm then mv.visitJumpInsn(GOTO, endLbl.asInstanceOf[Label])
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        true
      case _ => false

  /** Expression-form variant of `emitArmAsIfBranch`: emits GOTO endLbl instead of
   *  LRETURN/DRETURN so the arm value is left on the stack for use as a sub-expression. */
  private def emitArmBodyGuarded(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                                  instSlot: Int, mv: MethodVisitor,
                                  intTag: Int, allTagged: Boolean, endLbl: Label): Boolean =
    emitArmAsIfBranch(c, ctx, interp, instSlot, mv, intTag, allTagged, exprEndLbl = endLbl)

  private def emitArmAsIfBranch(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                                instSlot: Int, mv: MethodVisitor,
                                intTag: Int, allTagged: Boolean,
                                exprEndLbl: Label | Null = null): Boolean =
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = extractCtorName(ext.fun)
        if ctorName == null then return false
        val nextLbl = new Label
        if allTagged && intTag > 0 then
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
          emitIconst(mv, intTag)
          mv.visitJumpInsn(IF_ICMPNE, nextLbl)
        else
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
          mv.visitLdcInsn(ctorName)
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
          mv.visitJumpInsn(IFEQ, nextLbl)

        val argPats = ext.argClause.values.toArray
        val n       = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
            case _                      => return false
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return false

        val bindIsRef = Array.tabulate(n) { bi =>
          !bindNames(bi).startsWith("_unused$") && bindingIsRef(c.body, bindNames(bi), ctx)
        }

        val faSlot =
          if n > 0 then
            val s = ctx.allocSlot()
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
            mv.visitVarInsn(ASTORE, s)
            s
          else -1

        val newBindings = mutable.Map.empty[String, (Int, Boolean)]
        i = 0
        while i < n do
          if !bindNames(i).startsWith("_unused$") then
            val fname = fieldOrder(i)
            val isRef = bindIsRef(i)
            val bSlot = ctx.allocSlot()
            emitLoadField(mv, instSlot, faSlot, i, fname, n > 0)
            if isRef then
              mv.visitVarInsn(ASTORE, bSlot)
            else if ctx.isDouble then
              val rawSlot = ctx.allocSlot()
              mv.visitVarInsn(ASTORE, rawSlot)
              val LisD = new Label
              val Lm = new Label
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(INSTANCEOF, dblVInt)
              mv.visitJumpInsn(IFNE, LisD)
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitInsn(L2D)
              mv.visitJumpInsn(GOTO, Lm)
              mv.visitLabel(LisD)
              mv.visitVarInsn(ALOAD, rawSlot)
              mv.visitTypeInsn(CHECKCAST, dblVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
              mv.visitLabel(Lm)
              mv.visitVarInsn(DSTORE, bSlot)
            else
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitVarInsn(LSTORE, bSlot)
            newBindings(bindNames(i)) = (bSlot, isRef)
          i += 1

        val newCtx = ctx.withBindings(newBindings.toMap)
        c.cond match
          case Some(cond) =>
            if !walkBool(cond, newCtx, mv, nextLbl) then return false
          case None =>
        val bodyOk =
          if ctx.isDouble then walkDouble(c.body, newCtx, mv)
          else walkLong(c.body, newCtx, mv)
        if !bodyOk then return false
        if exprEndLbl != null then mv.visitJumpInsn(GOTO, exprEndLbl)
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        mv.visitLabel(nextLbl)
        true
      case _: Pat.Wildcard =>
        val nextLbl = new Label
        c.cond match
          case Some(cond) =>
            if !walkBool(cond, ctx, mv, nextLbl) then return false
          case None =>
        val bodyOk =
          if ctx.isDouble then walkDouble(c.body, ctx, mv)
          else walkLong(c.body, ctx, mv)
        if !bodyOk then return false
        if exprEndLbl != null then mv.visitJumpInsn(GOTO, exprEndLbl)
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        if c.cond.nonEmpty then mv.visitLabel(nextLbl)
        true
      case pv: Pat.Var =>
        val xName = pv.name.value
        if xName.isEmpty then return false
        val newCtx = ctx.withBindings(Map(xName -> (instSlot, true)))
        val nextLbl = new Label
        c.cond match
          case Some(cond) =>
            if !walkBool(cond, newCtx, mv, nextLbl) then return false
          case None =>
        val bodyOk =
          if ctx.isDouble then walkDouble(c.body, newCtx, mv)
          else walkLong(c.body, newCtx, mv)
        if !bodyOk then return false
        if exprEndLbl != null then mv.visitJumpInsn(GOTO, exprEndLbl)
        else mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        if c.cond.nonEmpty then mv.visitLabel(nextLbl)
        true
      case alt: Pat.Alternative =>
        def subPatTagInfo(p: Pat): (Int, String) | Null = p match
          case ext: Pat.Extract =>
            val cn = extractCtorName(ext.fun)
            if cn == null then return null
            if !ext.argClause.values.forall(_.isInstanceOf[Pat.Wildcard]) then return null
            (interp.typeTagMap.getOrElse(cn, 0), cn)
          case _: Pat.Wildcard => (0, "")
          case _: Pat.Var      => (0, "")
          case _ => null
        val lInfo = subPatTagInfo(alt.lhs)
        if lInfo == null then return false
        val rInfo = subPatTagInfo(alt.rhs)
        if rInfo == null then return false
        val bodyLbl = new Label
        val nextLbl = new Label
        val (lTag, lName) = lInfo
        if lName.isEmpty then mv.visitJumpInsn(GOTO, bodyLbl)
        else if lTag > 0 then
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
          emitIconst(mv, lTag)
          mv.visitJumpInsn(IF_ICMPEQ, bodyLbl)
        else
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
          mv.visitLdcInsn(lName)
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
          mv.visitJumpInsn(IFNE, bodyLbl)
        val (rTag, rName) = rInfo
        if rName.nonEmpty then
          if rTag > 0 then
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
            emitIconst(mv, rTag)
            mv.visitJumpInsn(IF_ICMPNE, nextLbl)
          else
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
            mv.visitLdcInsn(rName)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
            mv.visitJumpInsn(IFEQ, nextLbl)
        mv.visitLabel(bodyLbl)
        c.cond match
          case Some(cond) => if !walkBool(cond, ctx, mv, nextLbl) then return false
          case None =>
        val bodyOk = if ctx.isDouble then walkDouble(c.body, ctx, mv) else walkLong(c.body, ctx, mv)
        if !bodyOk then return false
        mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        mv.visitLabel(nextLbl)
        true
      case _ => false

  private def emitRefMatchBody(tm: Term.Match, ctx: GenCtx, interp: Interpreter,
                               mv: MethodVisitor): Boolean =
    val scrutName = tm.expr match
      case n: Term.Name => n.value
      case _            => return false
    if !ctx.params.contains(scrutName) then return false
    val scrutSlot = ctx.slotOf(scrutName)
    if scrutSlot < 0 then return false
    val cases = tm.casesBlock.cases
    val casesArr = cases.toArray
    if casesArr.isEmpty then return false
    val wildcardIdx = casesArr.indexWhere(_.pat.isInstanceOf[Pat.Var])
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false

    mv.visitVarInsn(ALOAD, scrutSlot)
    mv.visitTypeInsn(CHECKCAST, instVInt)
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, instSlot)

    val tags = resolveArmTags(casesArr, interp)
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
    val hasAnyGuard = casesArr.exists(_.cond.nonEmpty)

    if hasAnyGuard then
      var ci = 0
      var rem = cases
      while rem.nonEmpty do
        if !emitRefArmAsIfBranch(rem.head, ctx, interp, instSlot, mv,
                                  if allTagged then tags(ci) else 0, allTagged) then return false
        ci += 1
        rem = rem.tail
      if wildcardIdx < 0 then
        emitThrow(mv, "AsmJitBackend: no guarded ref case matched")
      return true

    val armLbls = Array.fill(casesArr.length)(new Label)
    val throwLbl = new Label
    val defLbl = if wildcardIdx >= 0 then armLbls(wildcardIdx) else throwLbl

    if allTagged then
      mv.visitVarInsn(ALOAD, instSlot)
      mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
      emitSwitch(mv, tags, armLbls, defLbl)
    else
      mv.visitVarInsn(ALOAD, instSlot)
      mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
      emitStringChain(mv, cases, armLbls, defLbl)

    var ci = 0
    var rem = cases
    while rem.nonEmpty do
      mv.visitLabel(armLbls(ci))
      if !emitRefArmBody(rem.head, ctx, interp, instSlot, mv) then return false
      ci += 1
      rem = rem.tail

    if wildcardIdx < 0 then
      mv.visitLabel(throwLbl)
      emitThrow(mv, "AsmJitBackend: no ref case matched")
    true

  private def emitRefArmAsIfBranch(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                                    instSlot: Int, mv: MethodVisitor,
                                    intTag: Int, allTagged: Boolean): Boolean =
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = extractCtorName(ext.fun)
        if ctorName == null then return false
        val nextLbl = new Label
        if allTagged && intTag > 0 then
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeTag", "()I", false)
          emitIconst(mv, intTag)
          mv.visitJumpInsn(IF_ICMPNE, nextLbl)
        else
          mv.visitVarInsn(ALOAD, instSlot)
          mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "typeName", "()Ljava/lang/String;", false)
          mv.visitLdcInsn(ctorName)
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
          mv.visitJumpInsn(IFEQ, nextLbl)
        val argPats = ext.argClause.values.toArray
        val n = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
            case _                      => return false
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return false
        val fieldTypes = interp.typeFieldTypes.getOrElse(ctorName, Nil).toArray
        def isPrimField(fi: Int): Boolean =
          fi < fieldTypes.length && (fieldTypes(fi) match
            case "Int" | "Long" | "Double" | "Boolean" => true
            case _ => false)
        def usesName(tree: scala.meta.Tree, name: String): Boolean =
          var hit = false
          def loop(t: scala.meta.Tree): Unit =
            if !hit then t match
              case Term.Name(n) if n == name => hit = true
              case _ => t.children.foreach(loop)
          loop(tree)
          hit
        val faSlot =
          if n > 0 then
            val s = ctx.allocSlot()
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
            mv.visitVarInsn(ASTORE, s)
            s
          else -1
        val newBindings = mutable.Map.empty[String, (Int, Boolean)]
        i = 0
        while i < n do
          val needed = !bindNames(i).startsWith("_unused$") &&
            (usesName(c.body, bindNames(i)) || c.cond.exists(g => usesName(g, bindNames(i))))
          if needed then
            val bSlot = ctx.allocSlot()
            val isRef = !isPrimField(i)
            emitLoadField(mv, instSlot, faSlot, i, fieldOrder(i), n > 0)
            if isRef then mv.visitVarInsn(ASTORE, bSlot)
            else
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitVarInsn(LSTORE, bSlot)
            newBindings(bindNames(i)) = (bSlot, isRef)
          i += 1
        val newCtx = ctx.withBindings(newBindings.toMap)
        c.cond match
          case Some(cond) => if !walkBool(cond, newCtx, mv, nextLbl) then return false
          case None =>
        if !walkRef(c.body, newCtx, mv) then return false
        mv.visitInsn(ARETURN)
        mv.visitLabel(nextLbl)
        true
      case _: Pat.Wildcard =>
        val nextLbl = new Label
        c.cond match
          case Some(cond) => if !walkBool(cond, ctx, mv, nextLbl) then return false
          case None =>
        if !walkRef(c.body, ctx, mv) then return false
        mv.visitInsn(ARETURN)
        if c.cond.nonEmpty then mv.visitLabel(nextLbl)
        true
      case pv: Pat.Var =>
        val xName = pv.name.value
        if xName.isEmpty then return false
        val newCtx = ctx.withBindings(Map(xName -> (instSlot, true)))
        val nextLbl = new Label
        c.cond match
          case Some(cond) => if !walkBool(cond, newCtx, mv, nextLbl) then return false
          case None =>
        if !walkRef(c.body, newCtx, mv) then return false
        mv.visitInsn(ARETURN)
        if c.cond.nonEmpty then mv.visitLabel(nextLbl)
        true
      case _ => false

  private def emitRefArmBody(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                             instSlot: Int, mv: MethodVisitor): Boolean =
    if c.cond.nonEmpty then return false
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = extractCtorName(ext.fun)
        if ctorName == null then return false
        val argPats = ext.argClause.values.toArray
        val n = argPats.length
        val bindNames = new Array[String](n)
        var i = 0
        while i < n do
          argPats(i) match
            case Pat.Var(Term.Name(bn)) => bindNames(i) = bn
            case _: Pat.Wildcard        => bindNames(i) = s"_unused$$_$i"
            case _                      => return false
          i += 1
        val fieldOrder = interp.typeFieldOrder.getOrElse(ctorName, Nil).toArray
        if fieldOrder.length != n then return false
        val fieldTypes = interp.typeFieldTypes.getOrElse(ctorName, Nil).toArray
        def isPrimField(fi: Int): Boolean =
          fi < fieldTypes.length && (fieldTypes(fi) match
            case "Int" | "Long" | "Double" | "Boolean" => true
            case _ => false)
        def usesName(tree: scala.meta.Tree, name: String): Boolean =
          var hit = false
          def loop(t: scala.meta.Tree): Unit =
            if !hit then t match
              case Term.Name(n) if n == name => hit = true
              case _ => t.children.foreach(loop)
          loop(tree)
          hit

        val faSlot =
          if n > 0 then
            val s = ctx.allocSlot()
            mv.visitVarInsn(ALOAD, instSlot)
            mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fieldsArr", s"()[L$valueInt;", false)
            mv.visitVarInsn(ASTORE, s)
            s
          else -1

        val newBindings = mutable.Map.empty[String, (Int, Boolean)]
        i = 0
        while i < n do
          if !bindNames(i).startsWith("_unused$") && usesName(c.body, bindNames(i)) then
            val bSlot = ctx.allocSlot()
            val isRef = !isPrimField(i)
            emitLoadField(mv, instSlot, faSlot, i, fieldOrder(i), n > 0)
            if isRef then
              mv.visitVarInsn(ASTORE, bSlot)
            else
              mv.visitTypeInsn(CHECKCAST, intVInt)
              mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
              mv.visitVarInsn(LSTORE, bSlot)
            newBindings(bindNames(i)) = (bSlot, isRef)
          i += 1

        val newCtx = ctx.withBindings(newBindings.toMap)
        if !walkRef(c.body, newCtx, mv) then return false
        mv.visitInsn(ARETURN)
        true
      case Pat.Var(Term.Name(xName)) =>
        val newCtx = ctx.withBindings(Map(xName -> (instSlot, true)))
        if !walkRef(c.body, newCtx, mv) then return false
        mv.visitInsn(ARETURN)
        true
      case _ => false

  private def emitLoadField(mv: MethodVisitor, instSlot: Int, faSlot: Int,
                             idx: Int, fname: String, hasArr: Boolean): Unit =
    if hasArr then
      val Lmap = new Label; val Lend = new Label
      mv.visitVarInsn(ALOAD, faSlot)
      mv.visitJumpInsn(IFNULL, Lmap)
      mv.visitVarInsn(ALOAD, faSlot)
      emitIconst(mv, idx)
      mv.visitInsn(AALOAD); mv.visitJumpInsn(GOTO, Lend)
      mv.visitLabel(Lmap)
      emitMapApply(mv, instSlot, fname)
      mv.visitLabel(Lend)
    else
      emitMapApply(mv, instSlot, fname)

  private def emitMapApply(mv: MethodVisitor, instSlot: Int, fname: String): Unit =
    mv.visitVarInsn(ALOAD, instSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, instVInt, "fields", "()Lscala/collection/immutable/Map;", false)
    mv.visitLdcInsn(fname)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/immutable/Map",
      "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true)

  // ── TCO ───────────────────────────────────────────────────────────────────

  private def tryTcoBody(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case ti: Term.If =>
      val rt = asSelfRecur(ti.thenp, ctx.funName)
      val re = asSelfRecur(ti.elsep, ctx.funName)
      (rt, re) match
        case (Some(_), Some(_)) => false
        case (Some(args), None) => emitTcoLoop(ti.cond, ti.elsep, args, ctx, mv, invertExit = true)
        case (None, Some(args)) => emitTcoLoop(ti.cond, ti.thenp, args, ctx, mv, invertExit = false)
        case _                  => false
    case _ => false

  private def asSelfRecur(t: Term, fn: String): Option[List[Term]] = t match
    case ap: Term.Apply => ap.fun match
      case fnN: Term.Name if fnN.value == fn => Some(ap.argClause.values)
      case _                                 => None
    case _ => None

  private def emitTcoLoop(cond: Term, base: Term, recurArgs: List[Term],
                          ctx: GenCtx, mv: MethodVisitor, invertExit: Boolean): Boolean =
    if recurArgs.length != ctx.paramNames.length then return false
    // Allocate temp slots for new arg values.
    val tmpSlots = Array.fill(ctx.paramNames.length)(ctx.allocSlot())
    val Lhead = new Label; val Lexit = new Label
    mv.visitLabel(Lhead)
    if invertExit then
      if !walkBool(cond, ctx, mv, Lexit) then return false  // cond false → exit
    else
      // exit when cond is true: jump past the exit when cond is false
      val Ltmp = new Label
      if !walkBool(cond, ctx, mv, Ltmp) then return false
      mv.visitJumpInsn(GOTO, Lexit)
      mv.visitLabel(Ltmp)

    // Evaluate new args into temps.
    var i = 0; var rem = recurArgs
    while rem.nonEmpty do
      val ok = if ctx.paramIsRef(i) then walkRef(rem.head, ctx, mv)
               else if ctx.isDouble  then walkDouble(rem.head, ctx, mv)
               else                       walkLong(rem.head, ctx, mv)
      if !ok then return false
      if ctx.paramIsRef(i) then mv.visitVarInsn(ASTORE, tmpSlots(i))
      else if ctx.isDouble  then mv.visitVarInsn(DSTORE, tmpSlots(i))
      else                       mv.visitVarInsn(LSTORE, tmpSlots(i))
      i += 1; rem = rem.tail

    // Copy temps to param slots.
    i = 0
    while i < ctx.paramNames.length do
      if ctx.paramIsRef(i) then
        mv.visitVarInsn(ALOAD, tmpSlots(i)); mv.visitVarInsn(ASTORE, ctx.paramSlots(i))
      else if ctx.isDouble then
        mv.visitVarInsn(DLOAD, tmpSlots(i)); mv.visitVarInsn(DSTORE, ctx.paramSlots(i))
      else
        mv.visitVarInsn(LLOAD, tmpSlots(i)); mv.visitVarInsn(LSTORE, ctx.paramSlots(i))
      i += 1

    mv.visitJumpInsn(GOTO, Lhead)
    mv.visitLabel(Lexit)
    val baseOk = if ctx.isDouble then walkDouble(base, ctx, mv) else walkLong(base, ctx, mv)
    if !baseOk then return false
    mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN); true

  private def bindingIsRef(armBody: Term, bindingName: String, ctx: GenCtx): Boolean =
    var hit = false
    def walk(t: scala.meta.Tree): Unit =
      if !hit then t match
        case Term.Apply.After_4_6_0(Term.Name(fnName), ac) =>
          var idx = 0
          ac.values.foreach { arg =>
            arg match
              case Term.Name(n) if n == bindingName && callParamIsRef(fnName, idx, ctx) =>
                hit = true
              case other =>
                walk(other)
            idx += 1
          }
        case _ => t.children.foreach(walk)
    walk(armBody); hit

  // ── Switch/string-chain helpers ───────────────────────────────────────────

  private def extractCtorName(t: Term): String | Null = t match
    case Term.Name(n)                     => n
    case Term.Select(_, Term.Name(n))     => n
    case _                                => null

  private def resolveArmTags(cases: Array[scala.meta.Case], interp: Interpreter): Array[Int] =
    cases.map { c =>
      c.pat match
        case ext: scala.meta.Pat.Extract =>
          val name = extractCtorName(ext.fun)
          if name == null then 0 else interp.typeTagMap.getOrElse(name, 0)
        case _ => 0
    }

  private def emitSwitch(mv: MethodVisitor, tags: Array[Int],
                         labels: Array[Label], defLbl: Label): Unit =
    if tags.isEmpty then { mv.visitJumpInsn(GOTO, defLbl); return }
    val min = tags.min; val max = tags.max
    if (max - min) < 4 * tags.length then
      val table = Array.fill(max - min + 1)(defLbl)
      for i <- tags.indices do table(tags(i) - min) = labels(i)
      mv.visitTableSwitchInsn(min, max, defLbl, table*)
    else
      val sorted = tags.zip(labels).sortBy(_._1)
      mv.visitLookupSwitchInsn(defLbl, sorted.map(_._1), sorted.map(_._2))

  private def emitStringChain(mv: MethodVisitor, cases: List[scala.meta.Case],
                               labels: Array[Label], defLbl: Label): Unit =
    // Stack top = String (typeName). Dup for each comparison.
    var ci = 0; var rem = cases
    while rem.nonEmpty do
      val name = rem.head.pat match
        case ext: scala.meta.Pat.Extract =>
          val n = extractCtorName(ext.fun)
          if n == null then "" else n
        case _ => ""
      if name.nonEmpty then
        val next = new Label
        mv.visitInsn(DUP)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(IFEQ, next)
        mv.visitInsn(POP)
        mv.visitJumpInsn(GOTO, labels(ci))
        mv.visitLabel(next)
      ci += 1; rem = rem.tail
    mv.visitInsn(POP)
    mv.visitJumpInsn(GOTO, defLbl)

  // ── Global read callsites ─────────────────────────────────────────────────

  private def emitGlobalLong(name: String, mv: MethodVisitor): Unit =
    mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
    mv.visitLdcInsn(name)
    mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "readGlobalLong", "(Ljava/lang/String;)J", false)

  private def emitGlobalDouble(name: String, mv: MethodVisitor): Unit =
    mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
    mv.visitLdcInsn(name)
    mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "readGlobalDouble", "(Ljava/lang/String;)D", false)

  private def emitGlobalRef(name: String, mv: MethodVisitor): Unit =
    mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
    mv.visitLdcInsn(name)
    mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "readGlobalRef", "(Ljava/lang/String;)Ljava/lang/Object;", false)

  // ── Misc bytecode helpers ─────────────────────────────────────────────────

  private def emitIconst(mv: MethodVisitor, v: Int): Unit = v match
    case 0 => mv.visitInsn(ICONST_0); case 1 => mv.visitInsn(ICONST_1)
    case 2 => mv.visitInsn(ICONST_2); case 3 => mv.visitInsn(ICONST_3)
    case 4 => mv.visitInsn(ICONST_4); case 5 => mv.visitInsn(ICONST_5)
    case _ if v >= -128 && v <= 127   => mv.visitIntInsn(BIPUSH, v)
    case _ if v >= -32768 && v <= 32767 => mv.visitIntInsn(SIPUSH, v)
    case _                             => mv.visitLdcInsn(v)

  private def emitThrow(mv: MethodVisitor, msg: String): Unit =
    mv.visitTypeInsn(NEW, "java/lang/RuntimeException")
    mv.visitInsn(DUP); mv.visitLdcInsn(msg)
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false)
    mv.visitInsn(ATHROW)

  // ── while-loop JIT ────────────────────────────────────────────────────────

  private val whileCache = new java.util.IdentityHashMap[Term, AnyRef]()
  private val WhileMiss  = new AnyRef

  override def tryCompileWhileLong(
    cond:   Term,
    names:  Array[String],
    rhs:    Array[Term],
    interp: Interpreter | Null,
    locals: Map[String, Value]
  ): WhileJitEntry | Null =
    if !enabled then return null
    val cached = whileCache.get(cond)
    if cached eq WhileMiss then return null
    if cached != null then return cached.asInstanceOf[WhileJitEntry]

    val n     = classCounter.incrementAndGet()
    val cname = s"scalascript/interpreter/vm/jit/asm/AsmWhile$$$n"
    val pureFns = mutable.LinkedHashMap.empty[String, WhilePureFn]
    // Local val-bound InstanceV names (e.g. `val v = Vec(3,4)` inside the
    // benched fn) so walkWhileRefArg can route them through the ref-call lane;
    // EvalRuntime re-resolves the actual value from the frame at run time.
    val localRefs = locals.iterator.collect { case (k, _: Value.InstanceV) => k }.toSet
    val ctx   = WhileCtx(names, interp, pureFns, cname, localRefs = localRefs)

    val condEmit = walkWhileBool(cond, ctx)
    if condEmit == null then { whileCache.put(cond, WhileMiss); return null }

    val rhsEmits = new Array[MethodVisitor => Unit](names.length)
    var k = 0
    while k < names.length do
      val e = walkWhileSlot(rhs(k), ctx)
      if e == null then { whileCache.put(cond, WhileMiss); return null }
      rhsEmits(k) = e
      k += 1

    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object",
      Array("scalascript/interpreter/vm/jit/WhileLongRunFn"))
    val init0 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init0.visitCode()
    init0.visitVarInsn(ALOAD, 0)
    init0.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init0.visitInsn(RETURN); init0.visitMaxs(1, 1); init0.visitEnd()
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "runStatic", "([J)V", null, null)
    mv.visitCode()
    emitWhileRefPreamble(ctx, mv, names.length)

    // array → named locals (slots 1..1+2*n, since slot 0 = the long[] ref)
    var i = 0
    while i < names.length do
      mv.visitVarInsn(ALOAD, 0); emitIconst(mv, i)
      mv.visitInsn(LALOAD); mv.visitVarInsn(LSTORE, 1 + i * 2)
      i += 1

    val Lhead = new Label; val Lend = new Label
    mv.visitLabel(Lhead)
    condEmit(mv, Lend)
    i = 0
    while i < names.length do
      rhsEmits(i)(mv); mv.visitVarInsn(LSTORE, 1 + i * 2)
      i += 1
    mv.visitJumpInsn(GOTO, Lhead)
    mv.visitLabel(Lend)

    i = 0
    while i < names.length do
      mv.visitVarInsn(ALOAD, 0); emitIconst(mv, i)
      mv.visitVarInsn(LLOAD, 1 + i * 2); mv.visitInsn(LASTORE)
      i += 1
    mv.visitInsn(RETURN); mv.visitMaxs(0, 0); mv.visitEnd()
    val imv0 = cw.visitMethod(ACC_PUBLIC, "run", "([J)V", null, null)
    imv0.visitCode()
    imv0.visitVarInsn(ALOAD, 1)
    imv0.visitMethodInsn(INVOKESTATIC, cname, "runStatic", "([J)V", false)
    imv0.visitInsn(RETURN); imv0.visitMaxs(0, 0); imv0.visitEnd()

    for (_, pf) <- pureFns do pf.emit(cw)
    cw.visitEnd()

    val bytes  = cw.toByteArray
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls    = try loader.define(cname.replace('/', '.'), bytes)
                 catch case _: Throwable => { whileCache.put(cond, WhileMiss); return null }
    val runFn0: WhileLongRunFn | Null =
      try cls.getConstructor().newInstance().asInstanceOf[WhileLongRunFn]
      catch case _: Throwable => null
    if runFn0 == null then
      whileCache.put(cond, WhileMiss)
      return null
    val refFnsArr = resolveWhileRefFns(ctx, interp)
    if refFnsArr == null then { whileCache.put(cond, WhileMiss); return null }
    val refObjFnsArr = resolveWhileRefObjFns(ctx, interp)
    if refObjFnsArr == null then { whileCache.put(cond, WhileMiss); return null }
    val entry = new WhileJitEntry(
      runFn0,
      new Array[Long](names.length),
      if ctx.refNames.isEmpty then Array.empty[String] else ctx.refNames.toArray,
      refFnsArr,
      refObjFnsArr
    )
    whileCache.put(cond, entry.asInstanceOf[AnyRef])
    entry

  // ── Stream.emit while-loop JIT ──────────────────────────────────────────

  private val whileEmitCache = new java.util.IdentityHashMap[Term, AnyRef]()
  private val WhileEmitMiss  = new AnyRef

  override def tryCompileWhileLongEmit(
    cond:        Term,
    emitArgs:    Array[Term],
    allSlots:    Array[String],
    assignNames: Array[String],
    rhs:         Array[Term],
    interp:      Interpreter | Null
  ): WhileLongEmitRunFn | Null =
    if !enabled then return null
    val cached = whileEmitCache.get(cond)
    if cached eq WhileEmitMiss then return null
    if cached != null then return cached.asInstanceOf[WhileLongEmitRunFn]

    val nSlots  = allSlots.length
    val pureFns = mutable.LinkedHashMap.empty[String, WhilePureFn]
    val n       = classCounter.incrementAndGet()
    val cname   = s"scalascript/interpreter/vm/jit/asm/AsmWhileEmit$$$n"
    val ctx     = WhileCtx(allSlots, interp, pureFns, cname)

    val condEmit = walkWhileBool(cond, ctx)
    if condEmit == null then { whileEmitCache.put(cond, WhileEmitMiss); return null }

    val emitEmits = new Array[SlotEmitter](emitArgs.length)
    var ei = 0
    while ei < emitArgs.length do
      val e = walkWhileSlot(emitArgs(ei), ctx)
      if e == null then { whileEmitCache.put(cond, WhileEmitMiss); return null }
      emitEmits(ei) = e
      ei += 1

    val rhsEmits = new Array[SlotEmitter](rhs.length)
    var k = 0
    while k < rhs.length do
      val e = walkWhileSlot(rhs(k), ctx)
      if e == null then { whileEmitCache.put(cond, WhileEmitMiss); return null }
      rhsEmits(k) = e
      k += 1

    // Emit-while supports only pure-Long slots (no ref fns, no ref globals).
    if ctx.refFnNames.nonEmpty || ctx.refObjFnNames.nonEmpty || ctx.refNames.nonEmpty then
      whileEmitCache.put(cond, WhileEmitMiss); return null

    // Resolve each assign target name to its slot index in `allSlots`.
    val assignSlotIdx = new Array[Int](assignNames.length)
    var ai = 0
    while ai < assignNames.length do
      var si = 0
      while si < nSlots && allSlots(si) != assignNames(ai) do si += 1
      if si >= nSlots then { whileEmitCache.put(cond, WhileEmitMiss); return null }
      assignSlotIdx(ai) = si
      ai += 1

    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object",
      Array("scalascript/interpreter/vm/jit/WhileLongEmitRunFn"))
    val init0 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init0.visitCode()
    init0.visitVarInsn(ALOAD, 0)
    init0.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init0.visitInsn(RETURN); init0.visitMaxs(1, 1); init0.visitEnd()

    // static int runStatic(long[] v, long[] buf, int bufLen)
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "runStatic", "([J[JI)I", null, null)
    mv.visitCode()
    // Move buf (param slot 1) and bufLen (param slot 2) to high locals so the
    // named long slots (each 2-wide at 1 + i*2) can reuse slots 1.. cleanly.
    // v stays at slot 0 (untouched) for the copy-in / copy-back.
    val bufSlot    = 1 + nSlots * 2
    val bufLenSlot = bufSlot + 1
    mv.visitVarInsn(ILOAD, 2); mv.visitVarInsn(ISTORE, bufLenSlot)
    mv.visitVarInsn(ALOAD, 1); mv.visitVarInsn(ASTORE, bufSlot)
    var i = 0
    while i < nSlots do
      mv.visitVarInsn(ALOAD, 0); emitIconst(mv, i)
      mv.visitInsn(LALOAD); mv.visitVarInsn(LSTORE, 1 + i * 2)
      i += 1

    val Lhead = new Label; val Lend = new Label
    mv.visitLabel(Lhead)
    condEmit(mv, Lend)
    // buf[bufLen++] = <emitArg> for each emit argument (post-increment index).
    ei = 0
    while ei < emitEmits.length do
      mv.visitVarInsn(ALOAD, bufSlot)
      mv.visitVarInsn(ILOAD, bufLenSlot)
      mv.visitIincInsn(bufLenSlot, 1)
      emitEmits(ei)(mv)
      mv.visitInsn(LASTORE)
      ei += 1
    // Trailing slot assigns (e.g. `i = i + 1`).
    k = 0
    while k < rhsEmits.length do
      rhsEmits(k)(mv); mv.visitVarInsn(LSTORE, 1 + assignSlotIdx(k) * 2)
      k += 1
    mv.visitJumpInsn(GOTO, Lhead)
    mv.visitLabel(Lend)

    i = 0
    while i < nSlots do
      mv.visitVarInsn(ALOAD, 0); emitIconst(mv, i)
      mv.visitVarInsn(LLOAD, 1 + i * 2); mv.visitInsn(LASTORE)
      i += 1
    mv.visitVarInsn(ILOAD, bufLenSlot)
    mv.visitInsn(IRETURN); mv.visitMaxs(0, 0); mv.visitEnd()

    val imv0 = cw.visitMethod(ACC_PUBLIC, "run", "([J[JI)I", null, null)
    imv0.visitCode()
    imv0.visitVarInsn(ALOAD, 1)
    imv0.visitVarInsn(ALOAD, 2)
    imv0.visitVarInsn(ILOAD, 3)
    imv0.visitMethodInsn(INVOKESTATIC, cname, "runStatic", "([J[JI)I", false)
    imv0.visitInsn(IRETURN); imv0.visitMaxs(0, 0); imv0.visitEnd()

    for (_, pf) <- pureFns do pf.emit(cw)
    cw.visitEnd()

    val bytes = cw.toByteArray
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = try loader.define(cname.replace('/', '.'), bytes)
              catch case _: Throwable => { whileEmitCache.put(cond, WhileEmitMiss); return null }
    val runFn0: WhileLongEmitRunFn | Null =
      try cls.getConstructor().newInstance().asInstanceOf[WhileLongEmitRunFn]
      catch case _: Throwable => null
    if runFn0 == null then { whileEmitCache.put(cond, WhileEmitMiss); return null }
    whileEmitCache.put(cond, runFn0.asInstanceOf[AnyRef])
    runFn0

  // ── mixed while + foreach fused JIT ─────────────────────────────────────

  private val whileMixedCache = new java.util.IdentityHashMap[Term, AnyRef]()
  private val WhileMixedMiss  = new AnyRef

  private def analyzeForeachApply(foreachApply: Term, accName: String): (String, String) | Null =
    foreachApply match
      case ta: Term.Apply =>
        ta.fun match
          case Term.Select(qual, Term.Name("foreach")) =>
            val receiverName = qual match
              case Term.Name(n) => n
              case _            => return null
            ta.argClause.values match
              case List(fn: Term.Function) if fn.paramClause.values.lengthCompare(1) == 0 =>
                val paramName = fn.paramClause.values.head.name.value
                if paramName.isEmpty then return null
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
                          case Term.ApplyInfix.After_4_6_0(Term.Name(`accName`), op, _, ac)
                              if op.value == "+" && ac.values.lengthCompare(1) == 0 =>
                            ac.values.head match
                              case ap: Term.Apply if ap.argClause.values.lengthCompare(1) == 0 =>
                                ap.fun match
                                  case Term.Name(fnName) =>
                                    ap.argClause.values.head match
                                      case Term.Name(`paramName`) => (receiverName, fnName)
                                      case _                      => null
                                  case _ => null
                              // identity fold: acc = acc + s  (empty fnName sentinel)
                              case Term.Name(`paramName`) => (receiverName, "")
                              case _ => null
                          case _ => null
                      case _ => null
                  case _ => null
              case _ => null
          case _ => null
      case _ => null

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
                          case Term.ApplyInfix.After_4_6_0(Term.Name(`accName`), op, _, ac)
                              if op.value == "+" && ac.values.lengthCompare(1) == 0 =>
                            ac.values.head match
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
    interp:       Interpreter
  ): WhileJitEntry | Null =
    if !enabled then return null
    val cached = whileMixedCache.get(foreachApply)
    if cached eq WhileMixedMiss then return null
    if cached != null then return cached.asInstanceOf[WhileJitEntry]

    val mapInfo = analyzeForeachMapApply(foreachApply, accName)
    if mapInfo != null then
      return tryCompileWhileMapForeach(cond, names, rhs, foreachApply, accIsDouble, mapInfo, interp)

    val info = analyzeForeachApply(foreachApply, accName)
    if info == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    val (receiverName, fnName) = info

    val receiverVal = interp.globals.getOrElse(receiverName, null)
    val receiverIsList = receiverVal.isInstanceOf[Value.ListV]
    val receiverIsSet  = !receiverIsList && receiverVal.isInstanceOf[Value.SetV]
    if !receiverIsList && !receiverIsSet then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val isIdentity = fnName.isEmpty
    val fnVal = if isIdentity then null else interp.globals.getOrElse(fnName, null)
    if !isIdentity && !fnVal.isInstanceOf[Value.FunV] then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null
    val funVTyped = if isIdentity then null else fnVal.asInstanceOf[Value.FunV]
    val doInline  = !isIdentity && canInlineMatchAccum(funVTyped, interp)
    val (fnObjToLong, fnObjToDouble) =
      if isIdentity || doInline then (null, null)
      else
        val jitR = tryCompile(funVTyped, interp)
        if jitR == null || jitR.direct == null then
          whileMixedCache.put(foreachApply, WhileMixedMiss)
          return null
        val otl = if !accIsDouble && jitR.direct.isInstanceOf[ObjToLong]  then jitR.direct.asInstanceOf[ObjToLong]  else null
        val otd = if  accIsDouble && jitR.direct.isInstanceOf[ObjToDouble] then jitR.direct.asInstanceOf[ObjToDouble] else null
        if (!accIsDouble && otl == null) || (accIsDouble && otd == null) then
          whileMixedCache.put(foreachApply, WhileMixedMiss)
          return null
        (otl, otd)

    val n = classCounter.incrementAndGet()
    val cname = s"scalascript/interpreter/vm/jit/asm/AsmWhileMixed$$$n"
    val pureFns = mutable.LinkedHashMap.empty[String, WhilePureFn]
    val ctx = WhileCtx(names, interp, pureFns, cname)

    val condEmit = walkWhileBool(cond, ctx)
    if condEmit == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }

    val rhsEmits = new Array[MethodVisitor => Unit](names.length)
    var k = 0
    while k < names.length do
      val e = walkWhileSlot(rhs(k), ctx)
      if e == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
      rhsEmits(k) = e
      k += 1

    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object",
      Array("scalascript/interpreter/vm/jit/WhileLongRunFn"))
    val init1 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init1.visitCode()
    init1.visitVarInsn(ALOAD, 0)
    init1.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init1.visitInsn(RETURN); init1.visitMaxs(1, 1); init1.visitEnd()
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "runStatic", "([J)V", null, null)
    mv.visitCode()

    val accSlotIdx = names.length
    val accLocal = 1 + names.length * 2
    val invSumLocal = accLocal + 2   // 2-wide (long/double) slot for LICM-hoisted sum
    var nextObjLocal = invSumLocal + 2
    def allocObjLocal(): Int =
      val s = nextObjLocal
      nextObjLocal += 1
      s

    val fnSlot: Int =
      if doInline || isIdentity then -1
      else
        val s = allocObjLocal()
        mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
        if accIsDouble then
          mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefDoubleFns",
            "()[Lscalascript/interpreter/vm/jit/ObjToDouble;", false)
          emitIconst(mv, 0)
          mv.visitInsn(AALOAD)
          mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/ObjToDouble")
        else
          mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefFns",
            "()[Lscalascript/interpreter/vm/jit/ObjToLong;", false)
          emitIconst(mv, 0)
          mv.visitInsn(AALOAD)
          mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/ObjToLong")
        mv.visitVarInsn(ASTORE, s)
        s

    val receiverSlot = allocObjLocal()
    mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
    mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefs", "()[Ljava/lang/Object;", false)
    emitIconst(mv, 0)
    mv.visitInsn(AALOAD)
    mv.visitTypeInsn(CHECKCAST,
      if receiverIsSet then "scalascript/interpreter/Value$SetV"
      else if doInline then "[Ljava/lang/Object;"   // pre-extracted Object[] for inline match path
      else "scalascript/interpreter/Value$ListV")
    mv.visitVarInsn(ASTORE, receiverSlot)

    k = 0
    while k < names.length do
      mv.visitVarInsn(ALOAD, 0)
      emitIconst(mv, k)
      mv.visitInsn(LALOAD)
      mv.visitVarInsn(LSTORE, 1 + k * 2)
      k += 1

    mv.visitVarInsn(ALOAD, 0)
    emitIconst(mv, accSlotIdx)
    mv.visitInsn(LALOAD)
    if accIsDouble then
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false)
      mv.visitVarInsn(DSTORE, accLocal)
    else mv.visitVarInsn(LSTORE, accLocal)

    // LICM pre-pass: compute invSumLocal = sum of fn over all receiver elements once,
    // before the outer while.  The sum is loop-invariant: receiver is val-bound, items
    // are immutable, and the outer while body can only modify local slots (not globals).
    if accIsDouble then
      mv.visitInsn(DCONST_0)
      mv.visitVarInsn(DSTORE, invSumLocal)
    else
      mv.visitInsn(LCONST_0)
      mv.visitVarInsn(LSTORE, invSumLocal)
    val foreachScratchSlot = allocObjLocal()
    if doInline then
      val elemSlot = allocObjLocal()
      var nextInlineLocal = nextObjLocal
      def allocInlineSlot(): Int = { val s2 = nextInlineLocal; nextInlineLocal += 2; s2 }
      val ok =
        if receiverIsSet then
          emitSetForeachAccumInline(mv, funVTyped, accIsDouble, receiverSlot, foreachScratchSlot, elemSlot, invSumLocal, allocInlineSlot, interp, cname)
        else
          emitArrayForeachAccumInline(mv, funVTyped, accIsDouble, receiverSlot, elemSlot, invSumLocal, allocInlineSlot, interp, cname)
      if !ok then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    else if receiverIsSet then emitSetForeachAccum(mv, receiverSlot, fnSlot, foreachScratchSlot, invSumLocal, accIsDouble, isIdentity)
    else emitListForeachAccum(mv, receiverSlot, fnSlot, foreachScratchSlot, invSumLocal, accIsDouble, isIdentity)

    // Outer while: tight loop — add constant invSumLocal per iteration.
    val outerHead = new Label
    val outerEnd = new Label
    mv.visitLabel(outerHead)
    condEmit(mv, outerEnd)
    if accIsDouble then
      mv.visitVarInsn(DLOAD, accLocal)
      mv.visitVarInsn(DLOAD, invSumLocal)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
    else
      mv.visitVarInsn(LLOAD, accLocal)
      mv.visitVarInsn(LLOAD, invSumLocal)
      mv.visitInsn(LADD)
      mv.visitVarInsn(LSTORE, accLocal)

    k = 0
    while k < names.length do
      rhsEmits(k)(mv)
      mv.visitVarInsn(LSTORE, 1 + k * 2)
      k += 1
    mv.visitJumpInsn(GOTO, outerHead)
    mv.visitLabel(outerEnd)

    k = 0
    while k < names.length do
      mv.visitVarInsn(ALOAD, 0)
      emitIconst(mv, k)
      mv.visitVarInsn(LLOAD, 1 + k * 2)
      mv.visitInsn(LASTORE)
      k += 1
    mv.visitVarInsn(ALOAD, 0)
    emitIconst(mv, accSlotIdx)
    if accIsDouble then
      mv.visitVarInsn(DLOAD, accLocal)
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false)
    else mv.visitVarInsn(LLOAD, accLocal)
    mv.visitInsn(LASTORE)
    mv.visitInsn(RETURN)
    val bytes =
      try
        mv.visitMaxs(0, 0); mv.visitEnd()
        val imv1 = cw.visitMethod(ACC_PUBLIC, "run", "([J)V", null, null)
        imv1.visitCode()
        imv1.visitVarInsn(ALOAD, 1)
        imv1.visitMethodInsn(INVOKESTATIC, cname, "runStatic", "([J)V", false)
        imv1.visitInsn(RETURN); imv1.visitMaxs(0, 0); imv1.visitEnd()
        for (_, pf) <- pureFns do pf.emit(cw)
        cw.visitEnd(); cw.toByteArray
      catch case _: Throwable => null
    if bytes == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }

    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = try loader.define(cname.replace('/', '.'), bytes)
              catch case _: Throwable => { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    val runFn1: WhileLongRunFn | Null =
      try cls.getConstructor().newInstance().asInstanceOf[WhileLongRunFn]
      catch case _: Throwable => null
    if runFn1 == null then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(
      runFn1,
      new Array[Long](names.length + 1),
      Array.empty[String],
      if fnObjToLong != null then Array(fnObjToLong) else Array.empty[ObjToLong],
      Array.empty[ObjToObject],
      if fnObjToDouble != null then Array(fnObjToDouble) else Array.empty[ObjToDouble],
      listPreExtract = doInline && !receiverIsSet
    )
    whileMixedCache.put(foreachApply, entry.asInstanceOf[AnyRef])
    entry

  private def tryCompileWhileMapForeach(
    cond:         Term,
    names:        Array[String],
    rhs:          Array[Term],
    foreachApply: Term,
    accIsDouble:  Boolean,
    mapInfo:      (String, Boolean),
    interp:       Interpreter
  ): WhileJitEntry | Null =
    val (mapName, useFirst) = mapInfo
    val mapVal = interp.globals.getOrElse(mapName, null)
    if !mapVal.isInstanceOf[Value.MapV] then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val n = classCounter.incrementAndGet()
    val cname = s"scalascript/interpreter/vm/jit/asm/AsmWhileMap$$$n"
    val pureFns = mutable.LinkedHashMap.empty[String, WhilePureFn]
    val ctx = WhileCtx(names, interp, pureFns, cname)

    val condEmit = walkWhileBool(cond, ctx)
    if condEmit == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }

    val rhsEmits = new Array[MethodVisitor => Unit](names.length)
    var k = 0
    while k < names.length do
      val e = walkWhileSlot(rhs(k), ctx)
      if e == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
      rhsEmits(k) = e
      k += 1

    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object",
      Array("scalascript/interpreter/vm/jit/WhileLongRunFn"))
    val init2 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
    init2.visitCode()
    init2.visitVarInsn(ALOAD, 0)
    init2.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
    init2.visitInsn(RETURN); init2.visitMaxs(1, 1); init2.visitEnd()
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "runStatic", "([J)V", null, null)
    mv.visitCode()

    val accSlotIdx = names.length
    val accLocal = 1 + names.length * 2
    val invSumLocal = accLocal + 2   // 2-wide slot for LICM-hoisted map sum
    var nextLocal = invSumLocal + 2
    def allocLocal(): Int =
      val s = nextLocal
      nextLocal += 1
      s

    val arraySlot = allocLocal()
    val lenSlot = allocLocal()
    val indexSlot = allocLocal()
    val itemSlot = allocLocal()

    mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
    mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefs", "()[Ljava/lang/Object;", false)
    emitIconst(mv, 0)
    mv.visitInsn(AALOAD)
    mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;")
    mv.visitVarInsn(ASTORE, arraySlot)
    mv.visitVarInsn(ALOAD, arraySlot)
    mv.visitInsn(ARRAYLENGTH)
    mv.visitVarInsn(ISTORE, lenSlot)

    k = 0
    while k < names.length do
      mv.visitVarInsn(ALOAD, 0)
      emitIconst(mv, k)
      mv.visitInsn(LALOAD)
      mv.visitVarInsn(LSTORE, 1 + k * 2)
      k += 1

    mv.visitVarInsn(ALOAD, 0)
    emitIconst(mv, accSlotIdx)
    mv.visitInsn(LALOAD)
    if accIsDouble then
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false)
      mv.visitVarInsn(DSTORE, accLocal)
    else mv.visitVarInsn(LSTORE, accLocal)

    // LICM pre-pass: map items are val-bound and immutable — compute their sum once.
    if accIsDouble then
      mv.visitInsn(DCONST_0)
      mv.visitVarInsn(DSTORE, invSumLocal)
    else
      mv.visitInsn(LCONST_0)
      mv.visitVarInsn(LSTORE, invSumLocal)
    emitMapArrayForeachAccum(mv, arraySlot, lenSlot, indexSlot, itemSlot, invSumLocal, accIsDouble)

    val outerHead = new Label
    val outerEnd = new Label
    mv.visitLabel(outerHead)
    condEmit(mv, outerEnd)
    if accIsDouble then
      mv.visitVarInsn(DLOAD, accLocal)
      mv.visitVarInsn(DLOAD, invSumLocal)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
    else
      mv.visitVarInsn(LLOAD, accLocal)
      mv.visitVarInsn(LLOAD, invSumLocal)
      mv.visitInsn(LADD)
      mv.visitVarInsn(LSTORE, accLocal)

    k = 0
    while k < names.length do
      rhsEmits(k)(mv)
      mv.visitVarInsn(LSTORE, 1 + k * 2)
      k += 1
    mv.visitJumpInsn(GOTO, outerHead)
    mv.visitLabel(outerEnd)

    k = 0
    while k < names.length do
      mv.visitVarInsn(ALOAD, 0)
      emitIconst(mv, k)
      mv.visitVarInsn(LLOAD, 1 + k * 2)
      mv.visitInsn(LASTORE)
      k += 1
    mv.visitVarInsn(ALOAD, 0)
    emitIconst(mv, accSlotIdx)
    if accIsDouble then
      mv.visitVarInsn(DLOAD, accLocal)
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false)
    else mv.visitVarInsn(LLOAD, accLocal)
    mv.visitInsn(LASTORE)
    mv.visitInsn(RETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    val imv2 = cw.visitMethod(ACC_PUBLIC, "run", "([J)V", null, null)
    imv2.visitCode()
    imv2.visitVarInsn(ALOAD, 1)
    imv2.visitMethodInsn(INVOKESTATIC, cname, "runStatic", "([J)V", false)
    imv2.visitInsn(RETURN); imv2.visitMaxs(0, 0); imv2.visitEnd()

    for (_, pf) <- pureFns do pf.emit(cw)
    cw.visitEnd()

    val bytes = cw.toByteArray
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = try loader.define(cname.replace('/', '.'), bytes)
              catch case _: Throwable => { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    val runFn2: WhileLongRunFn | Null =
      try cls.getConstructor().newInstance().asInstanceOf[WhileLongRunFn]
      catch case _: Throwable => null
    if runFn2 == null then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(
      runFn2,
      new Array[Long](names.length + 1),
      Array.empty[String],
      Array.empty[ObjToLong],
      Array.empty[ObjToObject],
      Array.empty[ObjToDouble],
      mapIsKeyMode = useFirst
    )
    whileMixedCache.put(foreachApply, entry.asInstanceOf[AnyRef])
    entry

  private def emitMapArrayForeachAccum(
    mv:          MethodVisitor,
    arraySlot:   Int,
    lenSlot:     Int,
    indexSlot:   Int,
    itemSlot:    Int,
    accLocal:    Int,
    accIsDouble: Boolean
  ): Unit =
    val head = new Label
    val done = new Label
    mv.visitInsn(ICONST_0)
    mv.visitVarInsn(ISTORE, indexSlot)
    mv.visitLabel(head)
    mv.visitVarInsn(ILOAD, indexSlot)
    mv.visitVarInsn(ILOAD, lenSlot)
    mv.visitJumpInsn(IF_ICMPGE, done)
    if accIsDouble then
      mv.visitVarInsn(ALOAD, arraySlot)
      mv.visitVarInsn(ILOAD, indexSlot)
      mv.visitInsn(AALOAD)
      mv.visitVarInsn(ASTORE, itemSlot)
      mv.visitVarInsn(DLOAD, accLocal)
      mv.visitVarInsn(ALOAD, itemSlot)
      mv.visitTypeInsn(INSTANCEOF, dblVInt)
      val intValue = new Label
      val haveValue = new Label
      mv.visitJumpInsn(IFEQ, intValue)
      mv.visitVarInsn(ALOAD, itemSlot)
      mv.visitTypeInsn(CHECKCAST, dblVInt)
      mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
      mv.visitJumpInsn(GOTO, haveValue)
      mv.visitLabel(intValue)
      mv.visitVarInsn(ALOAD, itemSlot)
      mv.visitTypeInsn(CHECKCAST, intVInt)
      mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
      mv.visitInsn(L2D)
      mv.visitLabel(haveValue)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
    else
      mv.visitVarInsn(LLOAD, accLocal)
      mv.visitVarInsn(ALOAD, arraySlot)
      mv.visitVarInsn(ILOAD, indexSlot)
      mv.visitInsn(AALOAD)
      mv.visitTypeInsn(CHECKCAST, intVInt)
      mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
      mv.visitInsn(LADD)
      mv.visitVarInsn(LSTORE, accLocal)
    mv.visitIincInsn(indexSlot, 1)
    mv.visitJumpInsn(GOTO, head)
    mv.visitLabel(done)

  private def emitListForeachAccum(
    mv:           MethodVisitor,
    receiverSlot: Int,
    fnSlot:       Int,
    itemsSlot:    Int,
    accLocal:     Int,
    accIsDouble:  Boolean,
    identity:     Boolean
  ): Unit =
    val head = new Label
    val done = new Label
    mv.visitVarInsn(ALOAD, receiverSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scalascript/interpreter/Value$ListV", "items",
      "()Lscala/collection/immutable/List;", false)
    mv.visitVarInsn(ASTORE, itemsSlot)
    mv.visitLabel(head)
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "isEmpty", "()Z", false)
    mv.visitJumpInsn(IFNE, done)
    if accIsDouble then mv.visitVarInsn(DLOAD, accLocal) else mv.visitVarInsn(LLOAD, accLocal)
    if !identity then mv.visitVarInsn(ALOAD, fnSlot)
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "head", "()Ljava/lang/Object;", false)
    if accIsDouble then
      if identity then
        mv.visitTypeInsn(CHECKCAST, dblVInt)
        mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
      else
        mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToDouble",
          "apply", "(Ljava/lang/Object;)D", true)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
    else
      if identity then
        mv.visitTypeInsn(CHECKCAST, intVInt)
        mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
      else
        mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToLong",
          "apply", "(Ljava/lang/Object;)J", true)
      mv.visitInsn(LADD)
      mv.visitVarInsn(LSTORE, accLocal)
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "tail",
      "()Lscala/collection/LinearSeq;", false)
    mv.visitTypeInsn(CHECKCAST, "scala/collection/immutable/List")
    mv.visitVarInsn(ASTORE, itemsSlot)
    mv.visitJumpInsn(GOTO, head)
    mv.visitLabel(done)

  private def emitSetForeachAccum(
    mv:           MethodVisitor,
    receiverSlot: Int,
    fnSlot:       Int,
    iterSlot:     Int,
    accLocal:     Int,
    accIsDouble:  Boolean,
    identity:     Boolean
  ): Unit =
    val head = new Label
    val done = new Label
    mv.visitVarInsn(ALOAD, receiverSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scalascript/interpreter/Value$SetV", "items",
      "()Lscala/collection/immutable/Set;", false)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/IterableOnce", "iterator",
      "()Lscala/collection/Iterator;", true)
    mv.visitVarInsn(ASTORE, iterSlot)
    mv.visitLabel(head)
    mv.visitVarInsn(ALOAD, iterSlot)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/Iterator", "hasNext", "()Z", true)
    mv.visitJumpInsn(IFEQ, done)
    if accIsDouble then mv.visitVarInsn(DLOAD, accLocal) else mv.visitVarInsn(LLOAD, accLocal)
    if !identity then mv.visitVarInsn(ALOAD, fnSlot)
    mv.visitVarInsn(ALOAD, iterSlot)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/Iterator", "next", "()Ljava/lang/Object;", true)
    if accIsDouble then
      if identity then
        mv.visitTypeInsn(CHECKCAST, dblVInt)
        mv.visitMethodInsn(INVOKEVIRTUAL, dblVInt, "v", "()D", false)
      else
        mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToDouble",
          "apply", "(Ljava/lang/Object;)D", true)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
    else
      if identity then
        mv.visitTypeInsn(CHECKCAST, intVInt)
        mv.visitMethodInsn(INVOKEVIRTUAL, intVInt, "v", "()J", false)
      else
        mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToLong",
          "apply", "(Ljava/lang/Object;)J", true)
      mv.visitInsn(LADD)
      mv.visitVarInsn(LSTORE, accLocal)
    mv.visitJumpInsn(GOTO, head)
    mv.visitLabel(done)

  // ── Inline match-body accumulation for tryCompileWhileMixed ─────────────

  /** True when `funV` has a 1-param match body where all arms are tagged and
   *  carry no guards.  Used as a pre-emission eligibility check so that callers
   *  can choose the inline path before starting to write bytecode. */
  private def canInlineMatchAccum(funV: Value.FunV, interp: Interpreter): Boolean =
    if funV.params.length != 1 then return false
    val paramName = funV.params.head
    val tm = funV.body match { case m: Term.Match => m; case _ => return false }
    val scrutName = tm.expr match { case n: Term.Name => n.value; case _ => return false }
    if scrutName != paramName then return false
    val casesArr = tm.casesBlock.cases.toArray
    if casesArr.isEmpty || casesArr.exists(_.cond.nonEmpty) then return false
    val wildcardIdx = casesArr.indexWhere(c => isCatchAllPat(c.pat))
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false
    val tags = resolveArmTags(casesArr, interp)
    (wildcardIdx >= 0 || tags.forall(_ != 0)) &&
      casesArr.forall { c => c.pat match
        case _: scala.meta.Pat.Extract | _: Pat.Wildcard | _: Pat.Var => true
        case _                                                          => false
      }

  /** Emit an inlined match-body accumulation step inside a foreach loop body.
   *  `elemSlot` holds the current element (Object, cast to InstanceV inside).
   *  `walkMatchExpr` computes the arm body (Double or Long) and leaves the
   *  result on the stack; we then add it to `accLocal` and jump to `listHead`
   *  (the inner list/set iteration loop head).
   *  `allocSlot` uses 2-unit increments for every local (mirrors GenCtx). */
  private def tryEmitInlineMatchAccum(
    funV:        Value.FunV,
    accIsDouble: Boolean,
    mv:          MethodVisitor,
    elemSlot:    Int,
    accLocal:    Int,
    listHead:    Label,
    allocSlot:   () => Int,
    interp:      Interpreter,
    cname:       String
  ): Boolean =
    val paramName = funV.params.head
    val tm        = funV.body.asInstanceOf[Term.Match]
    val coEmit    = new CoEmitState
    val inlineCtx = GenCtx(
      funName          = funV.name,
      staticMethodName = funV.name + "$$inline",
      params           = Set(paramName),
      paramNames       = Array(paramName),
      paramSlots       = Array(elemSlot),
      paramIsRef       = Array(true),
      isDouble         = accIsDouble,
      bindings         = Map.empty,
      interp           = interp,
      coEmit           = coEmit,
      selfClass        = cname,
      allocSlot        = allocSlot
    )
    if !walkMatchExpr(tm, inlineCtx, mv) then return false
    if coEmit.extraMethods.nonEmpty then return false
    if accIsDouble then
      mv.visitVarInsn(DLOAD, accLocal); mv.visitInsn(DADD); mv.visitVarInsn(DSTORE, accLocal)
    else
      mv.visitVarInsn(LLOAD, accLocal); mv.visitInsn(LADD); mv.visitVarInsn(LSTORE, accLocal)
    mv.visitJumpInsn(GOTO, listHead)
    true

  /** Array-backed list-foreach with inlined match dispatch.
   *  `arrSlot` holds a pre-extracted Object[] (EvalRuntime converts ListV → array before call).
   *  Uses indexed array access instead of isEmpty/head/tail — eliminates 3 virtual calls/element. */
  private def emitArrayForeachAccumInline(
    mv:          MethodVisitor,
    funV:        Value.FunV,
    accIsDouble: Boolean,
    arrSlot:     Int,
    elemSlot:    Int,
    accLocal:    Int,
    allocSlot:   () => Int,
    interp:      Interpreter,
    cname:       String
  ): Boolean =
    val lenLocal = allocSlot()  // 2-unit slot, used as int
    val idxLocal = allocSlot()  // 2-unit slot, used as int
    val head = new Label
    val done = new Label
    mv.visitVarInsn(ALOAD, arrSlot)
    mv.visitInsn(ARRAYLENGTH)
    mv.visitVarInsn(ISTORE, lenLocal)
    mv.visitInsn(ICONST_0)
    mv.visitVarInsn(ISTORE, idxLocal)
    mv.visitLabel(head)
    mv.visitVarInsn(ILOAD, idxLocal)
    mv.visitVarInsn(ILOAD, lenLocal)
    mv.visitJumpInsn(IF_ICMPGE, done)
    mv.visitVarInsn(ALOAD, arrSlot)
    mv.visitVarInsn(ILOAD, idxLocal)
    mv.visitInsn(AALOAD)
    mv.visitVarInsn(ASTORE, elemSlot)
    mv.visitIincInsn(idxLocal, 1)
    if !tryEmitInlineMatchAccum(funV, accIsDouble, mv, elemSlot, accLocal, head, allocSlot, interp, cname) then
      return false
    mv.visitLabel(done)
    true

  /** Set-foreach with inlined match dispatch — avoids ObjToDouble interface call. */
  private def emitSetForeachAccumInline(
    mv:           MethodVisitor,
    funV:         Value.FunV,
    accIsDouble:  Boolean,
    receiverSlot: Int,
    iterSlot:     Int,
    elemSlot:     Int,
    accLocal:     Int,
    allocSlot:    () => Int,
    interp:       Interpreter,
    cname:        String
  ): Boolean =
    val head = new Label
    val done = new Label
    mv.visitVarInsn(ALOAD, receiverSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scalascript/interpreter/Value$SetV", "items",
      "()Lscala/collection/immutable/Set;", false)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/IterableOnce", "iterator",
      "()Lscala/collection/Iterator;", true)
    mv.visitVarInsn(ASTORE, iterSlot)
    mv.visitLabel(head)
    mv.visitVarInsn(ALOAD, iterSlot)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/Iterator", "hasNext", "()Z", true)
    mv.visitJumpInsn(IFEQ, done)
    mv.visitVarInsn(ALOAD, iterSlot)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/Iterator", "next", "()Ljava/lang/Object;", true)
    mv.visitVarInsn(ASTORE, elemSlot)
    if !tryEmitInlineMatchAccum(funV, accIsDouble, mv, elemSlot, accLocal, head, allocSlot, interp, cname) then
      return false
    mv.visitLabel(done)
    true

  // ── While-loop walker context and types ──────────────────────────────────

  private case class WhileCtx(
    slots:      Array[String],
    interp:     Interpreter | Null,
    pureFns:    mutable.LinkedHashMap[String, WhilePureFn],
    selfClass:  String,
    isCallee:   Boolean = false,
    localRefs:  Set[String] = Set.empty
  ):
    val refNames:      mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val refFnNames:    mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val refObjFnNames: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    var refSlots:      Array[Int] = Array.empty
    var refFnSlots:    Array[Int] = Array.empty
    var refObjFnSlots: Array[Int] = Array.empty

    def refIdx(name: String): Int =
      val i = refNames.indexOf(name)
      if i >= 0 then i else { refNames += name; refNames.length - 1 }

    def refFnIdx(name: String): Int =
      val i = refFnNames.indexOf(name)
      if i >= 0 then i else { refFnNames += name; refFnNames.length - 1 }

    def refObjFnIdx(name: String): Int =
      val i = refObjFnNames.indexOf(name)
      if i >= 0 then i else { refObjFnNames += name; refObjFnNames.length - 1 }

  private case class WhilePureFn(emit: ClassWriter => Unit)

  // CondEmitter: emits a conditional jump to `ifFalse` when condition is false.
  private type CondEmitter = (MethodVisitor, Label) => Unit
  // SlotEmitter: emits a long-typed value onto the stack.
  private type SlotEmitter = MethodVisitor => Unit
  // RefEmitter: emits an Object-typed value onto the stack.
  private type RefEmitter = MethodVisitor => Unit

  private def emitWhileRefPreamble(ctx: WhileCtx, mv: MethodVisitor, nLongSlots: Int): Unit =
    var nextLocal = 1 + nLongSlots * 2
    def nextObjSlot(): Int =
      val s = nextLocal
      nextLocal += 1
      s

    if ctx.refFnNames.nonEmpty then
      val arrSlot = nextObjSlot()
      ctx.refFnSlots = Array.fill(ctx.refFnNames.length)(0)
      mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
      mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefFns",
        "()[Lscalascript/interpreter/vm/jit/ObjToLong;", false)
      mv.visitVarInsn(ASTORE, arrSlot)
      var i = 0
      while i < ctx.refFnNames.length do
        val slot = nextObjSlot()
        ctx.refFnSlots(i) = slot
        mv.visitVarInsn(ALOAD, arrSlot)
        emitIconst(mv, i)
        mv.visitInsn(AALOAD)
        mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/ObjToLong")
        mv.visitVarInsn(ASTORE, slot)
        i += 1

    if ctx.refObjFnNames.nonEmpty then
      val arrSlot = nextObjSlot()
      ctx.refObjFnSlots = Array.fill(ctx.refObjFnNames.length)(0)
      mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
      mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefObjFns",
        "()[Lscalascript/interpreter/vm/jit/ObjToObject;", false)
      mv.visitVarInsn(ASTORE, arrSlot)
      var i = 0
      while i < ctx.refObjFnNames.length do
        val slot = nextObjSlot()
        ctx.refObjFnSlots(i) = slot
        mv.visitVarInsn(ALOAD, arrSlot)
        emitIconst(mv, i)
        mv.visitInsn(AALOAD)
        mv.visitTypeInsn(CHECKCAST, "scalascript/interpreter/vm/jit/ObjToObject")
        mv.visitVarInsn(ASTORE, slot)
        i += 1

    if ctx.refNames.nonEmpty then
      val arrSlot = nextObjSlot()
      ctx.refSlots = Array.fill(ctx.refNames.length)(0)
      mv.visitFieldInsn(GETSTATIC, globalsInt, "MODULE$", s"L$globalsInt;")
      mv.visitMethodInsn(INVOKEVIRTUAL, globalsInt, "getRefs", "()[Ljava/lang/Object;", false)
      mv.visitVarInsn(ASTORE, arrSlot)
      var i = 0
      while i < ctx.refNames.length do
        val slot = nextObjSlot()
        ctx.refSlots(i) = slot
        mv.visitVarInsn(ALOAD, arrSlot)
        emitIconst(mv, i)
        mv.visitInsn(AALOAD)
        mv.visitVarInsn(ASTORE, slot)
        i += 1

  private def walkWhileBool(t: Term, ctx: WhileCtx): CondEmitter | Null = t match
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkWhileBool(inner, ctx)
        case _           => null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac) if ac.values.lengthCompare(1) == 0 =>
      op.value match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          val l = walkWhileSlot(lhs, ctx);          if l == null then return null
          val r = walkWhileSlot(ac.values.head, ctx); if r == null then return null
          val inv = invertCmp(op.value)
          (mv, ifFalse) => { l(mv); r(mv); mv.visitInsn(LCMP); mv.visitJumpInsn(inv, ifFalse) }
        case "&&" =>
          val l = walkWhileBool(lhs, ctx);          if l == null then return null
          val r = walkWhileBool(ac.values.head, ctx); if r == null then return null
          (mv, ifFalse) => { l(mv, ifFalse); r(mv, ifFalse) }
        case "||" =>
          val l = walkWhileBool(lhs, ctx);          if l == null then return null
          val r = walkWhileBool(ac.values.head, ctx); if r == null then return null
          (mv, ifFalse) =>
            val Ltmp = new Label; val Lskip = new Label
            l(mv, Ltmp); mv.visitJumpInsn(GOTO, Lskip)
            mv.visitLabel(Ltmp); r(mv, ifFalse); mv.visitLabel(Lskip)
        case _ => null
    case _ => null

  private def walkWhileSlot(t: Term, ctx: WhileCtx): SlotEmitter | Null = t match
    case Lit.Int(v)  => mv => mv.visitLdcInsn(v.toLong)
    case Lit.Long(v) => mv => mv.visitLdcInsn(v)
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkWhileSlot(inner, ctx)
        case _           => null
    case tn: Term.Name =>
      var idx = 0
      while idx < ctx.slots.length do
        if ctx.slots(idx) == tn.value then
          val fi = idx
          // Callee static methods `(J)J`/`(JJ)J` have params at slot 0, 2, ...
          // The main `run([J)V` has a long[] at slot 0, so params start at slot 1.
          val slot = if ctx.isCallee then fi * 2 else 1 + fi * 2
          return mv => mv.visitVarInsn(LLOAD, slot)
        idx += 1
      null
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, ac) if ac.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" =>
          val l = walkWhileSlot(lhs, ctx);          if l == null then return null
          val r = walkWhileSlot(ac.values.head, ctx); if r == null then return null
          val ins = longArith(op.value)
          mv => { l(mv); r(mv); mv.visitInsn(ins) }
        case _ => null
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      val a = walkWhileSlot(arg, ctx); if a == null then return null
      if op.value == "-" then mv => { a(mv); mv.visitInsn(LNEG) } else a
    case ti: Term.If =>
      val c = walkWhileBool(ti.cond, ctx); if c == null then return null
      val a = walkWhileSlot(ti.thenp, ctx); if a == null then return null
      val b = walkWhileSlot(ti.elsep, ctx); if b == null then return null
      mv =>
        val Le = new Label; val Ld = new Label
        c(mv, Le); a(mv); mv.visitJumpInsn(GOTO, Ld)
        mv.visitLabel(Le); b(mv); mv.visitLabel(Ld)
    case tm: Term.Match if ctx.interp != null && !ctx.isCallee =>
      val scrutRef = walkWhileRefArg(tm.expr, ctx)
      if scrutRef == null then return null
      val scrutName = tm.expr match
        case n: Term.Name => n.value
        case _            => return null
      val methodName = "fn_imatch_" + Integer.toUnsignedString(System.identityHashCode(tm))
      if !ctx.pureFns.contains(methodName) then
        ctx.pureFns(methodName) = WhilePureFn(cw => emitWhileInlineMatchHelper(cw, methodName, scrutName, tm, ctx))
      val sc = ctx.selfClass
      mv =>
        scrutRef(mv)
        mv.visitMethodInsn(INVOKESTATIC, sc, methodName, "(Ljava/lang/Object;)J", false)
    case ap: Term.Apply =>
      ap.fun match
        case fnName: Term.Name if ctx.interp != null =>
          val nargs = ap.argClause.values.length
          if nargs != 1 && nargs != 2 then return null
          val args  = ap.argClause.values
          if nargs == 1 && !ctx.isCallee then
            val refArg = walkWhileRefArg(args.head, ctx)
            if refArg != null then
              val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
              if !fnV.isInstanceOf[Value.FunV] then return null
              val fn = fnV.asInstanceOf[Value.FunV]
              if fn.params.length != 1 then return null
              val jitR = tryCompile(fn, ctx.interp)
              if jitR == null || !jitR.paramIsRef(0) || jitR.resultIsDouble ||
                 jitR.direct == null || !jitR.direct.isInstanceOf[ObjToLong]
              then return null
              val fi = ctx.refFnIdx(fnName.value)
              return mv =>
                mv.visitVarInsn(ALOAD, ctx.refFnSlots(fi))
                refArg(mv)
                mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToLong",
                  "apply", "(Ljava/lang/Object;)J", true)

          val san   = pureFnName(fnName.value)
          val a0    = walkWhileSlot(args.head, ctx); if a0 == null then return null
          val a1Opt: Option[SlotEmitter] =
            if nargs == 2 then
              val r = walkWhileSlot(args(1), ctx); if r == null then return null else Some(r)
            else None
          if !ctx.pureFns.contains(san) then
            val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
            if (fnV eq null) || !fnV.isInstanceOf[Value.FunV] then return null
            val fn = fnV.asInstanceOf[Value.FunV]
            if fn.params.length != nargs then return null
            if fn.usingParams.nonEmpty || fn.returnsThrows then return null
            if fn.defaults.nonEmpty && fn.defaults.exists(_.nonEmpty) then return null
            if fn.paramTypes.nonEmpty && fn.paramTypes.exists(_.endsWith("*")) then return null
            val calleeCtx = WhileCtx(fn.params.toArray, ctx.interp, ctx.pureFns, ctx.selfClass, isCallee = true)
            val bodyEmit  = walkWhileSlot(fn.body, calleeCtx); if bodyEmit == null then return null
            val desc = if nargs == 1 then "(J)J" else "(JJ)J"
            ctx.pureFns(san) = WhilePureFn(cw => {
              val m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, san, desc, null, null)
              m.visitCode(); bodyEmit(m); m.visitInsn(LRETURN); m.visitMaxs(0, 0); m.visitEnd()
            })
          val desc = if nargs == 1 then "(J)J" else "(JJ)J"
          val sc   = ctx.selfClass
          if nargs == 1 then mv => { a0(mv); mv.visitMethodInsn(INVOKESTATIC, sc, san, desc, false) }
          else mv => { a0(mv); a1Opt.get(mv); mv.visitMethodInsn(INVOKESTATIC, sc, san, desc, false) }
        case _ => null
    case _ => null

  private def walkWhileRefArg(t: Term, ctx: WhileCtx): RefEmitter | Null = t match
    case tn: Term.Name if ctx.interp != null =>
      var si = 0
      while si < ctx.slots.length do
        if ctx.slots(si) == tn.value then return null
        si += 1
      ctx.interp.globals.getOrElse(tn.value, null) match
        case _: Value.InstanceV =>
          val ri = ctx.refIdx(tn.value)
          mv => mv.visitVarInsn(ALOAD, ctx.refSlots(ri))
        case _ if ctx.localRefs.contains(tn.value) =>
          val ri = ctx.refIdx(tn.value)
          mv => mv.visitVarInsn(ALOAD, ctx.refSlots(ri))
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
                  mv => mv.visitVarInsn(ALOAD, ctx.refSlots(ri))
                case _ => null
            case _ => null
        case _ => null
    case ap: Term.Apply if ctx.interp != null && !ctx.isCallee =>
      ap.fun match
        case fnName: Term.Name if ap.argClause.values.lengthCompare(1) == 0 =>
          val innerRef = walkWhileRefArg(ap.argClause.values.head, ctx)
          if innerRef == null then return null
          val fnV = ctx.interp.globals.getOrElse(fnName.value, null)
          if !fnV.isInstanceOf[Value.FunV] then return null
          val fn = fnV.asInstanceOf[Value.FunV]
          if fn.params.length != 1 then return null
          val jitR = tryCompile(fn, ctx.interp)
          if jitR == null || !jitR.paramIsRef(0) || jitR.resultIsDouble ||
             jitR.direct == null || !jitR.direct.isInstanceOf[ObjToObject]
          then return null
          val oi = ctx.refObjFnIdx(fnName.value)
          mv =>
            mv.visitVarInsn(ALOAD, ctx.refObjFnSlots(oi))
            innerRef(mv)
            mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToObject",
              "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true)
        case _ => null
    case _ => null

  private def emitWhileInlineMatchHelper(
    cw:        ClassWriter,
    methodName: String,
    scrutName:  String,
    tm:         Term.Match,
    whileCtx:   WhileCtx
  ): Unit =
    if whileCtx.interp == null then return
    var nextLocal = 2
    val coEmit = new CoEmitState
    coEmit.signatures.put(methodName, MethodSig(methodName, Array(scrutName), Array(true), isDouble = false))
    coEmit.emitted.add(methodName)
    val genCtx = GenCtx(
      funName          = methodName,
      staticMethodName = methodName,
      params           = Set(scrutName),
      paramNames       = Array(scrutName),
      paramSlots       = Array(0),
      paramIsRef       = Array(true),
      isDouble         = false,
      bindings         = Map.empty,
      interp           = whileCtx.interp,
      coEmit           = coEmit,
      selfClass        = whileCtx.selfClass,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s }
    )
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, "(Ljava/lang/Object;)J", null, null)
    mv.visitCode()
    val ok = emitMatchBody(tm, genCtx, whileCtx.interp, mv)
    if !ok then emitThrow(mv, "AsmJitBackend: inline while match failed")
    mv.visitMaxs(0, 0)
    mv.visitEnd()

  private def resolveWhileRefFns(ctx: WhileCtx, interp: Interpreter | Null): Array[ObjToLong] | Null =
    if ctx.refFnNames.isEmpty then return Array.empty[ObjToLong]
    if interp == null then return null
    val arr = new Array[ObjToLong](ctx.refFnNames.length)
    var i = 0
    while i < ctx.refFnNames.length do
      interp.globals.getOrElse(ctx.refFnNames(i), null) match
        case fn: Value.FunV =>
          val jitR = tryCompile(fn, interp)
          if jitR == null || jitR.direct == null || !jitR.direct.isInstanceOf[ObjToLong] then return null
          arr(i) = jitR.direct.asInstanceOf[ObjToLong]
        case _ => return null
      i += 1
    arr

  private def resolveWhileRefObjFns(ctx: WhileCtx, interp: Interpreter | Null): Array[ObjToObject] | Null =
    if ctx.refObjFnNames.isEmpty then return Array.empty[ObjToObject]
    if interp == null then return null
    val arr = new Array[ObjToObject](ctx.refObjFnNames.length)
    var i = 0
    while i < ctx.refObjFnNames.length do
      interp.globals.getOrElse(ctx.refObjFnNames(i), null) match
        case fn: Value.FunV =>
          val jitR = tryCompile(fn, interp)
          if jitR == null || jitR.direct == null || !jitR.direct.isInstanceOf[ObjToObject] then return null
          arr(i) = jitR.direct.asInstanceOf[ObjToObject]
        case _ => return null
      i += 1
    arr

  private def pureFnName(name: String): String =
    val sb = new StringBuilder("fn_")
    var i = 0
    while i < name.length do
      val c = name.charAt(i)
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
      then sb.append(c) else sb.append('_')
      i += 1
    sb.toString

  private def sanitize(name: String): String =
    val sb = new StringBuilder
    var i = 0
    while i < name.length do
      val c = name.charAt(i)
      if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
      then sb.append(c) else sb.append('_')
      i += 1
    if sb.isEmpty || (sb.charAt(0) >= '0' && sb.charAt(0) <= '9') then sb.insert(0, "fn_")
    sb.toString

  // ── InMemoryClassLoader ───────────────────────────────────────────────────

  private class InMemoryClassLoader(parent: ClassLoader) extends ClassLoader(parent):
    def define(name: String, bytes: Array[Byte]): Class[?] =
      defineClass(name, bytes, 0, bytes.length)
