package scalascript.interpreter.vm.jit

import org.objectweb.asm.{ClassWriter, MethodVisitor, Label}
import org.objectweb.asm.Opcodes.*

import scala.meta.{Term, Lit, Pat}
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

  private def isBoolReturning(t: Term): Boolean = t match
    case Term.ApplyInfix.After_4_6_0(_, op, _, _) =>
      val s = op.value
      s == "<" || s == "<=" || s == ">" || s == ">=" || s == "==" || s == "!=" || s == "&&" || s == "||"
    case ti: Term.If => isBoolReturning(ti.thenp) || isBoolReturning(ti.elsep)
    case _           => false

  private def determineInterface(n: Int, paramIsRef: Array[Boolean], isDouble: Boolean): String | Null =
    if n == 1 then
      if paramIsRef(0) then if isDouble then s"$jitPkg.ObjToDouble" else s"$jitPkg.ObjToLong"
      else if isDouble then s"$jitPkg.DoubleFn1" else s"$jitPkg.LongFn1"
    else if n == 2 && !paramIsRef(0) && !paramIsRef(1) then
      if isDouble then s"$jitPkg.DoubleFn2" else s"$jitPkg.LongFn2"
    else null

  // ── doCompile ─────────────────────────────────────────────────────────────

  private def doCompile(f: Value.FunV, interp: Interpreter): JitResult | Null =
    if f.usingParams.nonEmpty || f.returnsThrows then return null
    if isBoolReturning(f.body) then return null
    val paramSet   = f.params.toSet
    val paramIsRef = new Array[Boolean](f.params.length)
    def markRef(t: scala.meta.Tree): Unit = t match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name => val idx = f.params.indexOf(n.value); if idx >= 0 then paramIsRef(idx) = true
          case _            =>
        markRef(tm.expr); tm.casesBlock.cases.foreach(c => markRef(c.body))
      case _ => t.children.foreach(markRef)
    markRef(f.body)
    val isDouble   = bodyHasDoubleLit(f.body)
    val ifaceName  = determineInterface(f.params.length, paramIsRef, isDouble)

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

    val staticName = sanitize(f.name)
    val pureFns    = mutable.LinkedHashMap.empty[String, ClassWriter => Unit]
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
      pureFns          = pureFns,
      selfClass        = cname,
      allocSlot        = () => { val s = nextLocal; nextLocal += 2; s }
    )

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

    for (_, emitFn) <- pureFns do emitFn(cw)

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

  private def emitFnBody(f: Value.FunV, ctx: GenCtx, interp: Interpreter,
                         mv: MethodVisitor, isDouble: Boolean): Boolean =
    f.body match
      case tm: Term.Match if ctx.paramIsRef.exists(identity) =>
        emitMatchBody(tm, ctx, interp, mv)
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
    pureFns:          mutable.LinkedHashMap[String, ClassWriter => Unit],
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
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == ctx.funName =>
          emitSelfCall(ap.argClause.values, ctx, mv)
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
    val allTagged = tags.forall(_ != 0)
    val armLbls  = Array.fill(casesArr.length)(new Label)
    val defLbl   = new Label

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
      if !emitArmBody(rem.head, ctx, interp, instSlot, mv, exprForm = false, null) then return false
      ci += 1; rem = rem.tail

    mv.visitLabel(defLbl)
    emitThrow(mv, "AsmJitBackend: no case matched"); true

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
    val tags     = resolveArmTags(casesArr, ctx.interp)
    val allTagged = tags.forall(_ != 0)
    val armLbls  = Array.fill(casesArr.length)(new Label)
    val defLbl   = new Label
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

    mv.visitLabel(defLbl)
    emitThrow(mv, "AsmJitBackend: no case matched")
    mv.visitLabel(endLbl); true

  // ── Single arm emission ───────────────────────────────────────────────────

  private def emitArmBody(c: scala.meta.Case, ctx: GenCtx, interp: Interpreter,
                          instSlot: Int, mv: MethodVisitor,
                          exprForm: Boolean, endLbl: Label | Null): Boolean =
    if c.cond.nonEmpty then return false
    c.pat match
      case ext: scala.meta.Pat.Extract =>
        val ctorName = ext.fun match
          case Term.Name(n) => n; case _ => return false
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
          !bindNames(bi).startsWith("_unused$") && bindingIsRef(c.body, bindNames(bi), ctx.funName)
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

  private def bindingIsRef(armBody: Term, bindingName: String, funName: String): Boolean =
    var hit = false
    def walk(t: scala.meta.Tree): Unit =
      if !hit then t match
        case Term.Apply.After_4_6_0(Term.Name(`funName`), ac) =>
          ac.values.foreach { case Term.Name(n) if n == bindingName => hit = true; case o => walk(o) }
        case _ => t.children.foreach(walk)
    walk(armBody); hit

  // ── Switch/string-chain helpers ───────────────────────────────────────────

  private def resolveArmTags(cases: Array[scala.meta.Case], interp: Interpreter): Array[Int] =
    cases.map { c =>
      c.pat match
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(n) => interp.typeTagMap.getOrElse(n, 0)
          case _            => 0
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
        case ext: scala.meta.Pat.Extract => ext.fun match
          case Term.Name(n) => n; case _ => ""
        case _ => ""
      if name.nonEmpty then
        mv.visitInsn(DUP)
        mv.visitLdcInsn(name)
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false)
        mv.visitJumpInsn(IFNE, labels(ci))
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
  ): java.lang.reflect.Method | Null =
    if !enabled then return null
    val cached = whileCache.get(cond)
    if cached eq WhileMiss then return null
    if cached != null then return cached.asInstanceOf[java.lang.reflect.Method]

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

    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
    cw.visit(V21, ACC_PUBLIC | ACC_FINAL, cname, null, "java/lang/Object", Array.empty)
    val mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "([J)V", null, null)
    mv.visitCode()

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
    val result =
      try
        val m = cls.getMethod("run", classOf[Array[Long]])
        m.setAccessible(true)
        m
      catch case _: Throwable => null
    whileCache.put(cond, if result == null then WhileMiss else result.asInstanceOf[AnyRef])
    result

  // ── While-loop walker context and types ──────────────────────────────────

  private case class WhileCtx(
    slots:     Array[String],
    interp:    Interpreter | Null,
    pureFns:   mutable.LinkedHashMap[String, WhilePureFn],
    selfClass: String
  )

  private case class WhilePureFn(emit: ClassWriter => Unit)

  // CondEmitter: emits a conditional jump to `ifFalse` when condition is false.
  private type CondEmitter = (MethodVisitor, Label) => Unit
  // SlotEmitter: emits a long-typed value onto the stack.
  private type SlotEmitter = MethodVisitor => Unit

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
          return mv => mv.visitVarInsn(LLOAD, 1 + fi * 2)
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
    case ap: Term.Apply =>
      ap.fun match
        case fnName: Term.Name if ctx.interp != null =>
          val san   = pureFnName(fnName.value)
          val nargs = ap.argClause.values.length
          if nargs != 1 && nargs != 2 then return null
          val args  = ap.argClause.values
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
            val calleeCtx = WhileCtx(fn.params.toArray, ctx.interp, ctx.pureFns, ctx.selfClass)
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
