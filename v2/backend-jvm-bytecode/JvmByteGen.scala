package ssc.bytecode

import org.objectweb.asm.{ClassWriter, MethodVisitor, Opcodes, Label, Handle, Type as AsmType}
import ssc.{Term, Const, Value, Program}

/** CoreIR → JVM bytecode, milestone 2 (Phase 4 jvm-lane).
 *
 *  Structure compiled: Lit/Global/Local/Prim/App/Seq/If/Let/Ctor/Lam/Match/
 *  LetRec. Values stay `ssc.Value`; prims/apps are invokestatic into ssc.Emit.
 *
 *  ENV MODEL (hybrid): each compiled body receives the De Bruijn frame as a
 *  Value[] param; Let/Match bindings live in JVM SLOTS on top of it.
 *  Local(i): i < slotDepth → the slot; else array[len-1-(i-slotDepth)].
 *  A nested Lam/LetRec MATERIALIZES the slots (Emit.capture) so captured
 *  frames match VM extend-semantics exactly.
 *
 *  Lam sites emit invokedynamic (LambdaMetafactory) against the Emit.LamFn
 *  SAM targeting the body's static method — closure creation is one indy call
 *  plus Emit.clos; the resulting ClosV interops with the VM by construction. */
final class Unsupported(val form: String) extends RuntimeException(form)

object JvmByteGen:
  private val EMIT  = "ssc/Emit"
  private val LAMFN = "ssc/Emit$LamFn"
  private val VAL   = "ssc/Value"
  private val OBJ   = "java/lang/Object"
  private val GEN   = "ssc/gen/Entry"

  private val metafactory = new Handle(
    Opcodes.H_INVOKESTATIC,
    "java/lang/invoke/LambdaMetafactory", "metafactory",
    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
    false)

  private final case class Pending(name: String, body: Term, selfGlobal: String | Null = null, selfArity: Int = -1)

  private final class Gen(val cw: ClassWriter):
    val pending = collection.mutable.Queue.empty[Pending]
    var lamIdx = 0
    def freshLam(): String = { lamIdx += 1; s"lam$$$lamIdx" }
    /** Top-level Lam defs: name → (methodName, arity). Calls to these compile
     *  to DIRECT invokestatic — no global lookup, no ClosV, no Emit.app. */
    val defMethods = collection.mutable.HashMap.empty[String, (String, Int)]

  /** Emitter context for ONE method body. */
  private final class Ctx(val mv: MethodVisitor, val g: Gen):
    var nextSlot = 1                       // slot 0 = env array param
    private val stack = collection.mutable.ArrayBuffer.empty[Int]
    def slotDepth: Int = stack.length
    def slots: List[Int] = stack.toList
    def push(): Int = { val s = nextSlot; nextSlot += 1; stack += s; s }
    def pop(n: Int): Unit = stack.remove(stack.length - n, n)
    def slotFor(deBruijn: Int): Int = stack(stack.length - 1 - deBruijn)
    var selfGlobal: String | Null = null
    var selfArity: Int = -1
    var startLabel: Label | Null = null
    def saveSlots(): List[Int] = { val s = stack.toList; stack.clear(); s }
    def restoreSlots(s: List[Int]): Unit = { stack.clear(); stack ++= s }

  def emitProgram(p: Program): Array[Byte] =
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GEN, null, OBJ, null)
    val g = new Gen(cw)

    // pre-register def methods FIRST so entry and def bodies alike can call
    // them directly (invokestatic) — names issued once, no re-issuing.
    // Shadowed defs (same name repeated): the LAST one wins (VM registration
    // order semantics) and only IT gets a method — emitting both would
    // produce a duplicate method name.
    val lastDefs = p.defs.zipWithIndex
      .collect { case (d, i) if d.body.isInstanceOf[Term.Lam] => (d.name, i) }
      .groupBy(_._1).view.mapValues(_.map(_._2).max).toMap
    p.defs.zipWithIndex.foreach { (d, i) =>
      d.body match
        case Term.Lam(ar, _) if lastDefs.get(d.name).contains(i) =>
          g.defMethods(d.name) = (g.freshLam(), ar)
        case _ => ()
    }

    // entry(): compiled entry term with an EMPTY frame
    emitBody(g, "entry", p.entry, paramIsEnv = false)

    // install(): compiled ClosV for every top-level Lam def (overrides the
    // VM-compiled version in Emit.globalsRef; value defs stay VM-compiled)
    val installMv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "()V", null, null)
    installMv.visitCode()
    p.defs.zipWithIndex.foreach { (d, i) =>
      d.body match
        case Term.Lam(ar, body) if lastDefs.get(d.name).contains(i) =>
          val (m, _) = g.defMethods(d.name)
          g.pending.enqueue(Pending(m, body, selfGlobal = d.name, selfArity = ar))
          installMv.visitLdcInsn(d.name)
          installMv.visitLdcInsn(ar)
          installMv.visitInvokeDynamicInsn("call", s"()L$LAMFN;", metafactory,
            AsmType.getType(s"([L$VAL;)L$VAL;"),
            new Handle(Opcodes.H_INVOKESTATIC, GEN, m, s"([L$VAL;)L$VAL;", false),
            AsmType.getType(s"([L$VAL;)L$VAL;"))
          installMv.visitInsn(Opcodes.ICONST_0)
          installMv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
          installMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "clos", s"(IL$LAMFN;[L$VAL;)L$VAL;", false)
          installMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "registerGlobal", s"(Ljava/lang/String;L$VAL;)V", false)
        case _ => () // non-lam defs remain VM-compiled in globalsRef
    }
    installMv.visitInsn(Opcodes.RETURN)
    installMv.visitMaxs(0, 0); installMv.visitEnd()

    // drain pending lam bodies (may enqueue more)
    while g.pending.nonEmpty do
      val pnd = g.pending.dequeue()
      emitBody(g, pnd.name, pnd.body, paramIsEnv = true, selfGlobal = pnd.selfGlobal, selfArity = pnd.selfArity)

    cw.visitEnd()
    cw.toByteArray

  private def emitBody(g: Gen, name: String, body: Term, paramIsEnv: Boolean,
                       selfGlobal: String | Null = null, selfArity: Int = -1): Unit =
    val desc = if paramIsEnv then s"([L$VAL;)L$VAL;" else s"()L$VAL;"
    val mv = g.cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null)
    mv.visitCode()
    val ctx = new Ctx(mv, g)
    ctx.selfGlobal = selfGlobal
    ctx.selfArity = selfArity
    if !paramIsEnv then
      // no env param: slot 0 hosts an empty frame for uniformity
      mv.visitInsn(Opcodes.ICONST_0)
      mv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
      mv.visitVarInsn(Opcodes.ASTORE, 0)
    // self-tail-calls jump here with a rebound frame in slot 0
    val startL = new Label()
    mv.visitLabel(startL)
    ctx.startLabel = startL
    gen(body, ctx, tail = true)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()

  private def loadEnv(ctx: Ctx): Unit = ctx.mv.visitVarInsn(Opcodes.ALOAD, 0)

  /** Materialize slot values into a captured frame on the stack: Emit.capture(env, slotVals). */
  private def emitCapture(ctx: Ctx): Unit =
    val mv = ctx.mv
    loadEnv(ctx)
    val ss = ctx.slots
    mv.visitLdcInsn(ss.length)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
    ss.zipWithIndex.foreach { (slot, i) =>
      mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i)
      mv.visitVarInsn(Opcodes.ALOAD, slot)
      mv.visitInsn(Opcodes.AASTORE)
    }
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "capture", s"([L$VAL;[L$VAL;)[L$VAL;", false)

  private def emitLamFnRef(ctx: Ctx, methodName: String): Unit =
    ctx.mv.visitInvokeDynamicInsn("call", s"()L$LAMFN;", metafactory,
      AsmType.getType(s"([L$VAL;)L$VAL;"),
      new Handle(Opcodes.H_INVOKESTATIC, GEN, methodName, s"([L$VAL;)L$VAL;", false),
      AsmType.getType(s"([L$VAL;)L$VAL;"))

  private def gen(t: Term, ctx: Ctx, tail: Boolean = false): Unit =
    val mv = ctx.mv
    t match
      case Term.Lit(c) => c match
        case Const.CUnit     => call0(mv, "unitV")
        case Const.CBool(b)  => mv.visitInsn(if b then Opcodes.ICONST_1 else Opcodes.ICONST_0); callZ(mv, "boolV")
        case Const.CInt(n)   => mv.visitLdcInsn(n); callJ(mv, "intV")
        case Const.CFloat(d) => mv.visitLdcInsn(d); callD(mv, "floatV")
        case Const.CStr(s)   => mv.visitLdcInsn(s); callStr(mv, "strV")
        case other           => throw new Unsupported(s"lit:$other")
      case Term.Global(gname) =>
        mv.visitLdcInsn(gname); callStr(mv, "global")
      case Term.Local(i) =>
        if i < ctx.slotDepth then
          mv.visitVarInsn(Opcodes.ALOAD, ctx.slotFor(i))
        else
          // env[env.length - 1 - (i - slotDepth)]
          loadEnv(ctx)
          mv.visitInsn(Opcodes.DUP)
          mv.visitInsn(Opcodes.ARRAYLENGTH)
          mv.visitLdcInsn(1 + (i - ctx.slotDepth))
          mv.visitInsn(Opcodes.ISUB)
          mv.visitInsn(Opcodes.AALOAD)
      case Term.Lam(ar, body) =>
        val m = ctx.g.freshLam()
        ctx.g.pending.enqueue(Pending(m, body))
        mv.visitLdcInsn(ar)
        emitLamFnRef(ctx, m)
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "clos", s"(IL$LAMFN;[L$VAL;)L$VAL;", false)
      case Term.Seq(Nil) =>
        call0(mv, "unitV")
      case Term.Seq(ts) =>
        ts.zipWithIndex.foreach { (s, i) =>
          val last = i == ts.length - 1
          gen(s, ctx, tail && last)
          if !last then mv.visitInsn(Opcodes.POP)
        }
      case Term.If(c, a, b) =>
        gen(c, ctx); callVB(mv, "bool")
        val elseL = new Label(); val endL = new Label()
        mv.visitJumpInsn(Opcodes.IFEQ, elseL)
        gen(a, ctx, tail); mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(elseL); gen(b, ctx, tail); mv.visitLabel(endL)
      case Term.Let(rhs, body) =>
        rhs.foreach { r =>
          gen(r, ctx)
          val slot = ctx.push()
          mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
        gen(body, ctx, tail)
        ctx.pop(rhs.length)
      case Term.LetRec(lams, body) =>
        // materialize the current frame, then Emit.letrec(arities, fns, env)
        val entries = lams.map {
          case Term.Lam(ar, b) =>
            val m = ctx.g.freshLam()
            ctx.g.pending.enqueue(Pending(m, b))
            (ar, m)
          case other => throw new Unsupported("letrec:non-lam")
        }
        // arities array
        mv.visitLdcInsn(entries.length)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
        entries.zipWithIndex.foreach { case ((ar, _), i) =>
          mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i); mv.visitLdcInsn(ar); mv.visitInsn(Opcodes.IASTORE)
        }
        // fns array
        mv.visitLdcInsn(entries.length)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, LAMFN)
        entries.zipWithIndex.foreach { case ((_, m), i) =>
          mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i); emitLamFnRef(ctx, m); mv.visitInsn(Opcodes.AASTORE)
        }
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "letrec", s"([I[L$LAMFN;[L$VAL;)[L$VAL;", false)
        // the returned frame becomes the body's env: store into slot 0 of a
        // FRESH context region — simplest correct: overwrite slot 0 after
        // saving? Instead: new env slot + a child ctx view.
        // the returned frame becomes the body's env; the old slots were
        // MATERIALIZED into it, so the body runs with an EMPTY slot stack
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        val savedSlots = ctx.saveSlots()
        val savedSelf = ctx.selfGlobal
        ctx.selfGlobal = null // slot 0 no longer holds the def's own frame
        gen(body, ctx, tail)
        ctx.selfGlobal = savedSelf
        ctx.restoreSlots(savedSlots)
      case Term.While(cond, body) =>
        val startL = new Label(); val endL = new Label()
        mv.visitLabel(startL)
        gen(cond, ctx); callVB(mv, "bool")
        mv.visitJumpInsn(Opcodes.IFEQ, endL)
        gen(body, ctx); mv.visitInsn(Opcodes.POP)
        mv.visitJumpInsn(Opcodes.GOTO, startL)
        mv.visitLabel(endL)
        call0(mv, "unitV")
      case Term.Prim("__arith__", Term.Lit(Const.CStr(aop)) :: a :: b :: Nil) =>
        mv.visitLdcInsn(aop); gen(a, ctx); gen(b, ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "arith", s"(Ljava/lang/String;L$VAL;L$VAL;)L$VAL;", false)
      case Term.Prim(op, args) =>
        args.length match
          case 0 => mv.visitLdcInsn(op); callP(mv, "prim0", 0)
          case 1 => mv.visitLdcInsn(op); gen(args(0), ctx); callP(mv, "prim1", 1)
          case 2 => mv.visitLdcInsn(op); gen(args(0), ctx); gen(args(1), ctx); callP(mv, "prim2", 2)
          case 3 => mv.visitLdcInsn(op); gen(args(0), ctx); gen(args(1), ctx); gen(args(2), ctx); callP(mv, "prim3", 3)
          case _ => mv.visitLdcInsn(op); genArray(args, ctx); callArr(mv, "primN")
      // SELF-TAIL: rebind the frame and jump to the method start — constant
      // stack depth for arbitrarily deep tail recursion.
      case Term.App(Term.Global(gname), args)
          if tail && ctx.selfGlobal == gname && ctx.selfArity == args.length && ctx.startLabel != null =>
        mv.visitVarInsn(Opcodes.ALOAD, 0)         // current frame (env intact: lets live in slots)
        genArray(args, ctx)                        // evaluate args against the OLD frame
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "rebind", s"([L$VAL;[L$VAL;)[L$VAL;", false)
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        mv.visitJumpInsn(Opcodes.GOTO, ctx.startLabel.asInstanceOf[Label])
        // unreachable value for the verifier's stack shape at merge points
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitTypeInsn(Opcodes.CHECKCAST, VAL)
      case Term.App(Term.Global(gname), args) if ctx.g.defMethods.get(gname).exists(_._2 == args.length) =>
        val (m, _) = ctx.g.defMethods(gname)
        if tail then
          // MUTUAL-TAIL: return a Bounce instead of invoking — the caller's
          // consumer unrolls it in a loop, so mutual recursion runs at
          // constant stack. (Self-tail is handled by the GOTO arm above.)
          emitLamFnRef(ctx, m)
          genArray(args, ctx)
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "bounce", s"(L$LAMFN;[L$VAL;)L$VAL;", false)
        else
          // direct call to a compiled top-level def: invokestatic + unroll
          genArray(args, ctx)
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, m, s"([L$VAL;)L$VAL;", false)
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "unroll", s"(L$VAL;)L$VAL;", false)
      case Term.App(f, args) =>
        gen(f, ctx); genArray(args, ctx); callApp(mv)
      case Term.Ctor(tag, fields) =>
        mv.visitLdcInsn(tag); genArray(fields, ctx); callCtorArr(mv)
      case Term.Match(scrut, arms, default) =>
        gen(scrut, ctx)
        val scrutSlot = ctx.nextSlot; ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ASTORE, scrutSlot)
        val endL = new Label()
        val defaultL = new Label()
        // if !isData → default
        mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "isData", s"(L$VAL;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, defaultL)
        // tag/arity chain
        arms.foreach { arm =>
          val skipL = new Label()
          mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataTag", s"(L$VAL;)Ljava/lang/String;", false)
          mv.visitLdcInsn(arm.tag)
          mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", s"(L$OBJ;)Z", false)
          mv.visitJumpInsn(Opcodes.IFEQ, skipL)
          mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataArity", s"(L$VAL;)I", false)
          mv.visitLdcInsn(arm.arity)
          mv.visitJumpInsn(Opcodes.IF_ICMPNE, skipL)
          // bind fields (in order → Local(0)=last field, VM extend semantics)
          if arm.arity > 0 then
            mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataFields", s"(L$VAL;)[L$VAL;", false)
            val fieldsSlot = ctx.nextSlot; ctx.nextSlot += 1
            mv.visitVarInsn(Opcodes.ASTORE, fieldsSlot)
            (0 until arm.arity).foreach { i =>
              mv.visitVarInsn(Opcodes.ALOAD, fieldsSlot)
              mv.visitLdcInsn(i)
              mv.visitInsn(Opcodes.AALOAD)
              val s = ctx.push()
              mv.visitVarInsn(Opcodes.ASTORE, s)
            }
          gen(arm.body, ctx, tail)
          if arm.arity > 0 then ctx.pop(arm.arity)
          mv.visitJumpInsn(Opcodes.GOTO, endL)
          mv.visitLabel(skipL)
        }
        mv.visitLabel(defaultL)
        default match
          case Some(d) => gen(d, ctx, tail)
          case None =>
            mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataTag", s"(L$VAL;)Ljava/lang/String;", false)
            mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataArity", s"(L$VAL;)I", false)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "matchFail", s"(Ljava/lang/String;I)L$VAL;", false)
        mv.visitLabel(endL)
      case other => throw new Unsupported(other.getClass.getSimpleName)


  private def genArray(items: List[Term], ctx: Ctx): Unit =
    val mv = ctx.mv
    mv.visitLdcInsn(items.length)
    mv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
    items.zipWithIndex.foreach { (it, i) =>
      mv.visitInsn(Opcodes.DUP)
      mv.visitLdcInsn(i)
      gen(it, ctx)
      mv.visitInsn(Opcodes.AASTORE)
    }

  private def call0(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"()L$VAL;", false)
  private def callJ(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(J)L$VAL;", false)
  private def callD(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(D)L$VAL;", false)
  private def callZ(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(Z)L$VAL;", false)
  private def callStr(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(Ljava/lang/String;)L$VAL;", false)
  private def callVB(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(L$VAL;)Z", false)
  private def callP(mv: MethodVisitor, m: String, arity: Int) =
    val vs = (s"L$VAL;") * arity
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(Ljava/lang/String;$vs)L$VAL;", false)
  private def callArr(mv: MethodVisitor, m: String) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, m, s"(Ljava/lang/String;[L$VAL;)L$VAL;", false)
  private def callApp(mv: MethodVisitor) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "app", s"(L$VAL;[L$VAL;)L$VAL;", false)
  private def callCtorArr(mv: MethodVisitor) =
    mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "ctor", s"(Ljava/lang/String;[L$VAL;)L$VAL;", false)

  private final class GenLoader(parent: ClassLoader) extends ClassLoader(parent):
    def define(name: String, bytes: Array[Byte]): Class[?] =
      defineClass(name, bytes, 0, bytes.length)

  /** defineClass + install compiled defs + invoke entry(). */
  def runProgram(bytes: Array[Byte]): Value =
    val cls = new GenLoader(getClass.getClassLoader).define("ssc.gen.Entry", bytes)
    cls.getMethod("install").invoke(null)
    ssc.Emit.unroll(cls.getMethod("entry").invoke(null).asInstanceOf[Value])
