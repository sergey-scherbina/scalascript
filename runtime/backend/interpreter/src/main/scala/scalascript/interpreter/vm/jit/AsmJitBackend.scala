package scalascript.interpreter.vm.jit

import org.objectweb.asm.{ClassWriter, MethodVisitor, Label}
import org.objectweb.asm.Opcodes.*

import scala.meta.{Term, Lit, Pat, Stat, Defn}
import scala.collection.mutable
import scalascript.interpreter.{Interpreter, Value}

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
  private val instVInt  = "scalascript/interpreter/Value$InstanceV"
  private val intVInt   = "scalascript/interpreter/Value$IntV"
  private val dblVInt   = "scalascript/interpreter/Value$DoubleV"
  private val valueInt  = "scalascript/interpreter/Value"
  private val globalsInt = "scalascript/interpreter/vm/jit/JitGlobals$"
  private val jitPkg    = "scalascript.interpreter.vm.jit"

  // ── SPI surface ───────────────────────────────────────────────────────────

  override def tryCompile(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if !enabled || f.name.isEmpty then return null
    if f.params.length != 1 && f.params.length != 2 then return null
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
    if n == 1 then
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
        case (true,  true)  => null
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

  private def classifyParamRefs(f: Value.FunV): Array[Boolean] =
    val paramIsRef = new Array[Boolean](f.params.length)
    def markRef(t: scala.meta.Tree): Unit = t match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name =>
            val idx = f.params.indexOf(n.value)
            if idx >= 0 then paramIsRef(idx) = true
          case _ =>
        markRef(tm.expr)
        tm.casesBlock.cases.foreach(c => markRef(c.body))
      case _ => t.children.foreach(markRef)
    markRef(f.body)
    paramIsRef

  private def jitCompatibleSibling(f: Value.FunV): Boolean =
    f.name.nonEmpty &&
      (f.params.length == 1 || f.params.length == 2) &&
      f.usingParams.isEmpty &&
      !f.returnsThrows &&
      (f.defaults.isEmpty || f.defaults.forall(_.isEmpty)) &&
      (f.paramTypes.isEmpty || !f.paramTypes.exists(_.endsWith("*"))) &&
      !isBoolReturning(f.body)

  private def staticMethodName(name: String): String = sanitize(name)

  // ── doCompile ─────────────────────────────────────────────────────────────

  private def doCompile(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if f.usingParams.nonEmpty || f.returnsThrows then return null
    if isBoolReturning(f.body) then return null
    val paramSet   = f.params.toSet
    val paramIsRef = classifyParamRefs(f)
    val isDouble   = bodyHasDoubleLit(f.body)
    val ifaceName  = determineInterface(f.params.length, paramIsRef, isDouble)
    val coEmit     = new CoEmitState

    // Assign JVM local-variable slots. Always allocate 2 per variable (safe for
    // longs/doubles; wastes one slot for Object refs but is correct with COMPUTE_FRAMES).
    val paramSlots = new Array[Int](f.params.length)
    var nextSlot0  = 0
    for i <- 0 until f.params.length do
      paramSlots(i) = nextSlot0; nextSlot0 += 2
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
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s }
    )

    if f.params.length == 1 && paramIsRef(0) then
      f.body match
        case tm: Term.Match =>
          val refR = tryCompileObjToObject(tm, ctx, interp, cname)
          if refR != null then return refR
        case _ =>

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
    new JitResult(mh, paramIsRef, isDouble, direct)

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

  /** Direction A.5: process non-final `Defn.Val` stats, emitting LSTORE/DSTORE
   *  bytecode for each. Returns the updated GenCtx with the new bindings on
   *  success, or null if any stat is not a compilable val-binding. */
  private def emitValBindings(stats: List[Stat], ctx: GenCtx, mv: MethodVisitor): GenCtx | Null =
    var curCtx = ctx
    var rem = stats
    while rem.nonEmpty do
      rem.head match
        case Defn.Val(_, List(Pat.Var(n)), _, rhs: Term) =>
          val slot = curCtx.allocSlot()
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

  private def emitFnBody(f: Value.FunV, ctx: GenCtx, interp: Interpreter,
                         mv: MethodVisitor, isDouble: Boolean): Boolean =
    f.body match
      case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
        emitMatchBody(tm, ctx, interp, mv)
      case b: Term.Block if b.stats.lengthCompare(1) > 0 =>
        // Direction A.5: multi-stat block — emit val-bindings then the final expr.
        b.stats.last match
          case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
            val tailCtx = emitValBindings(b.stats.init, ctx, mv)
            tailCtx != null && emitMatchBody(tm, tailCtx, interp, mv)
          case last: Term =>
            val tailCtx = emitValBindings(b.stats.init, ctx, mv)
            tailCtx != null && (
              if isDouble then walkDouble(last, tailCtx, mv) && { mv.visitInsn(DRETURN); true }
              else             walkLong(last, tailCtx, mv)   && { mv.visitInsn(LRETURN); true }
            )
          case _ => false
      case other =>
        val tco = tryTcoBody(other.asInstanceOf[Term], ctx, mv)
        if tco then true
        else if isDouble then
          walkDouble(other.asInstanceOf[Term], ctx, mv) && { mv.visitInsn(DRETURN); true }
        else
          walkLong(other.asInstanceOf[Term], ctx, mv) && { mv.visitInsn(LRETURN); true }

  // ── Descriptor builder ────────────────────────────────────────────────────

  private def buildStaticDesc(paramIsRef: Array[Boolean], isDouble: Boolean): String =
    val sb = new StringBuilder("(")
    for r <- paramIsRef do
      if r then sb.append("Ljava/lang/Object;")
      else if isDouble then sb.append("D") else sb.append("J")
    sb.append(if isDouble then ")D" else ")J")
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
    allocSlot:        () => Int
  ):
    def isRefName(n: String): Boolean =
      bindings.get(n) match
        case Some((_, r)) => r
        case None => val i = paramNames.indexOf(n); i >= 0 && paramIsRef(i)

    def slotOf(n: String): Int =
      bindings.get(n) match
        case Some((s, _)) => s
        case None => val i = paramNames.indexOf(n); if i >= 0 then paramSlots(i) else -1

    def isParam(n: String): Boolean = params.contains(n) || bindings.contains(n)

    def withBindings(more: Map[String, (Int, Boolean)]): GenCtx = copy(bindings = bindings ++ more)

  // ── walkLong ─────────────────────────────────────────────────────────────

  private def walkLong(t: Term, ctx: GenCtx, mv: MethodVisitor): Boolean = t match
    case Lit.Int(v)  => mv.visitLdcInsn(v.toLong); true
    case Lit.Long(v) => mv.visitLdcInsn(v);        true
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
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name =>
          emitLongCall(fn.value, ap.argClause.values, ctx, mv)
        case _ => false
    case _ => false

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
      nextSlot0 += 2
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
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s }
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
    val scratch = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
      override def getCommonSuperClass(t1: String, t2: String): String = "java/lang/Object"
    }
    scratch.visit(V21, ACC_PUBLIC | ACC_FINAL,
      s"scalascript/interpreter/vm/jit/asm/AsmJitScratch$$${classCounter.incrementAndGet()}",
      null, "java/lang/Object", Array.empty)
    val ok =
      try
        val bodyOk = emitStaticFunction(scratch, ctx.selfClass, fn, sig, ctx)
        scratch.visitEnd()
        if bodyOk then scratch.toByteArray; bodyOk
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
    if sig == null || sig.isDouble || args.length != sig.paramNames.length then return false
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
    true

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
    case tn: Term.Name if ctx.isRefName(tn.value) =>
      val s = ctx.slotOf(tn.value); if s < 0 then false else { mv.visitVarInsn(ALOAD, s); true }
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => walkRef(inner, ctx, mv)
        case _           => false
    case _ => false

  // ── walkBool: emit jump-to-ifFalse when condition is false ───────────────

  private def walkBool(t: Term, ctx: GenCtx, mv: MethodVisitor, ifFalse: Label): Boolean =
    t match
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
      case _ => false

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

  // ── Match body (top-level: emits RETURN inside each arm) ─────────────────

  private def emitMatchBody(tm: Term.Match, ctx: GenCtx, interp: Interpreter,
                            mv: MethodVisitor): Boolean =
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
    val tags     = resolveArmTags(casesArr, interp)
    val wildcardIdx = casesArr.indexWhere(c => isCatchAllPat(c.pat))
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
    val armLbls  = Array.fill(casesArr.length)(new Label)
    val throwLbl = new Label
    val defLbl   = if wildcardIdx >= 0 then armLbls(wildcardIdx) else throwLbl

    if casesArr.exists(_.cond.nonEmpty) then
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
    val tags     = resolveArmTags(casesArr, ctx.interp)
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
    val armLbls  = Array.fill(casesArr.length)(new Label)
    val throwLbl = new Label
    val defLbl   = if wildcardIdx >= 0 then armLbls(wildcardIdx) else throwLbl
    val endLbl   = new Label

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

  private def emitArmAsIfBranch(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
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
        mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
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
        mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
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
        mv.visitInsn(if ctx.isDouble then DRETURN else LRETURN)
        if c.cond.nonEmpty then mv.visitLabel(nextLbl)
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
    if casesArr.isEmpty || casesArr.exists(_.cond.nonEmpty) then return false
    val wildcardIdx = casesArr.indexWhere(_.pat.isInstanceOf[Pat.Var])
    if wildcardIdx >= 0 && wildcardIdx != casesArr.length - 1 then return false

    mv.visitVarInsn(ALOAD, scrutSlot)
    mv.visitTypeInsn(CHECKCAST, instVInt)
    val instSlot = ctx.allocSlot()
    mv.visitVarInsn(ASTORE, instSlot)

    val tags = resolveArmTags(casesArr, interp)
    val allTagged = wildcardIdx < 0 && tags.forall(_ != 0)
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
    interp: Interpreter | Null
  ): WhileJitEntry | Null =
    if !enabled then return null
    val cached = whileCache.get(cond)
    if cached eq WhileMiss then return null
    if cached != null then return cached.asInstanceOf[WhileJitEntry]

    val n     = classCounter.incrementAndGet()
    val cname = s"scalascript/interpreter/vm/jit/asm/AsmWhile$$$n"
    val pureFns = mutable.LinkedHashMap.empty[String, WhilePureFn]
    val ctx   = WhileCtx(names, interp, pureFns, cname)

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
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array.empty)
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "([J)V", null, null)
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

    for (_, pf) <- pureFns do pf.emit(cw)
    cw.visitEnd()

    val bytes  = cw.toByteArray
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls    = try loader.define(cname.replace('/', '.'), bytes)
                 catch case _: Throwable => { whileCache.put(cond, WhileMiss); return null }
    val method =
      try
        val m = cls.getMethod("run", classOf[Array[Long]])
        m.setAccessible(true)
        m
      catch case _: Throwable => null
    if method == null then
      whileCache.put(cond, WhileMiss)
      return null
    val refFnsArr = resolveWhileRefFns(ctx, interp)
    if refFnsArr == null then { whileCache.put(cond, WhileMiss); return null }
    val refObjFnsArr = resolveWhileRefObjFns(ctx, interp)
    if refObjFnsArr == null then { whileCache.put(cond, WhileMiss); return null }
    val entry = new WhileJitEntry(
      method,
      if ctx.refNames.isEmpty then Array.empty[String] else ctx.refNames.toArray,
      refFnsArr,
      refObjFnsArr
    )
    whileCache.put(cond, entry.asInstanceOf[AnyRef])
    entry

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

    val fnVal = interp.globals.getOrElse(fnName, null)
    if !fnVal.isInstanceOf[Value.FunV] then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null
    val funVTyped = fnVal.asInstanceOf[Value.FunV]
    val doInline  = canInlineMatchAccum(funVTyped, interp)
    val (fnObjToLong, fnObjToDouble) =
      if doInline then (null, null)
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
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array.empty)
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "([J)V", null, null)
    mv.visitCode()

    val accSlotIdx = names.length
    val accLocal = 1 + names.length * 2
    var nextObjLocal = accLocal + 2
    def allocObjLocal(): Int =
      val s = nextObjLocal
      nextObjLocal += 1
      s

    val fnSlot: Int =
      if doInline then -1
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
      if receiverIsSet then "scalascript/interpreter/Value$SetV" else "scalascript/interpreter/Value$ListV")
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

    val outerHead = new Label
    val outerEnd = new Label
    mv.visitLabel(outerHead)
    condEmit(mv, outerEnd)
    val foreachScratchSlot = allocObjLocal()
    if doInline then
      val elemSlot = allocObjLocal()
      var nextInlineLocal = nextObjLocal
      def allocInlineSlot(): Int = { val s2 = nextInlineLocal; nextInlineLocal += 2; s2 }
      val ok =
        if receiverIsSet then
          emitSetForeachAccumInline(mv, funVTyped, accIsDouble, receiverSlot, foreachScratchSlot, elemSlot, accLocal, allocInlineSlot, interp, cname)
        else
          emitListForeachAccumInline(mv, funVTyped, accIsDouble, receiverSlot, foreachScratchSlot, elemSlot, accLocal, allocInlineSlot, interp, cname)
      if !ok then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    else if receiverIsSet then emitSetForeachAccum(mv, receiverSlot, fnSlot, foreachScratchSlot, accLocal, accIsDouble)
    else emitListForeachAccum(mv, receiverSlot, fnSlot, foreachScratchSlot, accLocal, accIsDouble)

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
        for (_, pf) <- pureFns do pf.emit(cw)
        cw.visitEnd(); cw.toByteArray
      catch case _: Throwable => null
    if bytes == null then { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }

    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = try loader.define(cname.replace('/', '.'), bytes)
              catch case _: Throwable => { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    val method =
      try
        val m = cls.getMethod("run", classOf[Array[Long]])
        m.setAccessible(true)
        m
      catch case _: Throwable => null
    if method == null then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(
      method,
      Array.empty[String],
      if fnObjToLong != null then Array(fnObjToLong) else Array.empty[ObjToLong],
      Array.empty[ObjToObject],
      if fnObjToDouble != null then Array(fnObjToDouble) else Array.empty[ObjToDouble]
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
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array.empty)
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "([J)V", null, null)
    mv.visitCode()

    val accSlotIdx = names.length
    val accLocal = 1 + names.length * 2
    var nextLocal = accLocal + 2
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

    val outerHead = new Label
    val outerEnd = new Label
    mv.visitLabel(outerHead)
    condEmit(mv, outerEnd)
    emitMapArrayForeachAccum(mv, arraySlot, lenSlot, indexSlot, itemSlot, accLocal, accIsDouble)

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

    for (_, pf) <- pureFns do pf.emit(cw)
    cw.visitEnd()

    val bytes = cw.toByteArray
    val loader = new InMemoryClassLoader(getClass.getClassLoader)
    val cls = try loader.define(cname.replace('/', '.'), bytes)
              catch case _: Throwable => { whileMixedCache.put(foreachApply, WhileMixedMiss); return null }
    val method =
      try
        val m = cls.getMethod("run", classOf[Array[Long]])
        m.setAccessible(true)
        m
      catch case _: Throwable => null
    if method == null then
      whileMixedCache.put(foreachApply, WhileMixedMiss)
      return null

    val entry = new WhileJitEntry(
      method,
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
    accIsDouble:  Boolean
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
    mv.visitVarInsn(ALOAD, fnSlot)
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "head", "()Ljava/lang/Object;", false)
    if accIsDouble then
      mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToDouble",
        "apply", "(Ljava/lang/Object;)D", true)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
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
    accIsDouble:  Boolean
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
    mv.visitVarInsn(ALOAD, fnSlot)
    mv.visitVarInsn(ALOAD, iterSlot)
    mv.visitMethodInsn(INVOKEINTERFACE, "scala/collection/Iterator", "next", "()Ljava/lang/Object;", true)
    if accIsDouble then
      mv.visitMethodInsn(INVOKEINTERFACE, "scalascript/interpreter/vm/jit/ObjToDouble",
        "apply", "(Ljava/lang/Object;)D", true)
      mv.visitInsn(DADD)
      mv.visitVarInsn(DSTORE, accLocal)
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

  /** List-foreach with inlined match dispatch — avoids ObjToDouble interface call. */
  private def emitListForeachAccumInline(
    mv:           MethodVisitor,
    funV:         Value.FunV,
    accIsDouble:  Boolean,
    receiverSlot: Int,
    itemsSlot:    Int,
    elemSlot:     Int,
    accLocal:     Int,
    allocSlot:    () => Int,
    interp:       Interpreter,
    cname:        String
  ): Boolean =
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
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "head", "()Ljava/lang/Object;", false)
    mv.visitVarInsn(ASTORE, elemSlot)
    mv.visitVarInsn(ALOAD, itemsSlot)
    mv.visitMethodInsn(INVOKEVIRTUAL, "scala/collection/immutable/List", "tail",
      "()Lscala/collection/LinearSeq;", false)
    mv.visitTypeInsn(CHECKCAST, "scala/collection/immutable/List")
    mv.visitVarInsn(ASTORE, itemsSlot)
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
    slots:     Array[String],
    interp:    Interpreter | Null,
    pureFns:   mutable.LinkedHashMap[String, WhilePureFn],
    selfClass: String,
    isCallee:  Boolean = false
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
