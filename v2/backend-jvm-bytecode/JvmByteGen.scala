package ssc.bytecode

import org.objectweb.asm.{ClassWriter, MethodVisitor, Opcodes, Type as AsmType, Label}
import ssc.{Term, Const, Value, Program}

/** CoreIR → JVM bytecode, milestone 1 (Phase 4 jvm-lane).
 *
 *  Scope: the ENTRY term's structural forms — Lit / Global / Local / Prim /
 *  App / Seq / If / Let / Ctor. Values stay `ssc.Value` on the JVM stack;
 *  every prim/application is ONE invokestatic into the `ssc.Emit` shims, so
 *  emission is push-args-and-call. Globals come from the existing VM compiler
 *  (hybrid milestone): `Emit.globalsRef` is set before invocation.
 *
 *  Unsupported forms throw Unsupported — the corpus probe counts coverage.
 *  Lam/Match/LetRec compilation is milestone 2. */
final class Unsupported(val form: String) extends RuntimeException(form)

object JvmByteGen:
  private val EMIT = "ssc/Emit"
  private val VAL  = "ssc/Value"
  private val OBJ  = "java/lang/Object"

  /** Emit a class `ssc.gen.Entry` with `public static Value entry()`. */
  def emitEntry(entry: Term): Array[Byte] =
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "ssc/gen/Entry", null, OBJ, null)
    val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "entry", s"()L$VAL;", null, null)
    mv.visitCode()
    val ctx = new Ctx(mv)
    gen(entry, ctx)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
    cw.visitEnd()
    cw.toByteArray

  /** Emitter context: De Bruijn frame → JVM local slots (static methods: slot 0 free). */
  private final class Ctx(val mv: MethodVisitor):
    private var next = 0
    private val stack = collection.mutable.ArrayBuffer.empty[Int]
    def depth: Int = stack.length
    def push(): Int = { val s = next; next += 1; stack += s; s }
    def pop(n: Int): Unit = stack.remove(stack.length - n, n)
    def slotOf(deBruijn: Int): Int = stack(stack.length - 1 - deBruijn)

  private def emitShim(mv: MethodVisitor, name: String, desc: String): Unit =
    // Emit is a Scala object: call through its MODULE$ instance method
    mv.visitFieldInsn(Opcodes.GETSTATIC, s"$EMIT$$", "MODULE$", s"L$EMIT$$;")
    // stack: args..., module — need module FIRST; simpler: swap for arity<=1, else recompute.
    throw new Unsupported("shim-call-ordering") // replaced below by the static-forwarder strategy

  private def gen(t: Term, ctx: Ctx): Unit =
    val mv = ctx.mv
    t match
      case Term.Lit(c) => c match
        case Const.CUnit     => call0(mv, "unitV")
        case Const.CBool(b)  => mv.visitInsn(if b then Opcodes.ICONST_1 else Opcodes.ICONST_0); callZ(mv, "boolV")
        case Const.CInt(n)   => mv.visitLdcInsn(n); callJ(mv, "intV")
        case Const.CFloat(d) => mv.visitLdcInsn(d); callD(mv, "floatV")
        case Const.CStr(s)   => mv.visitLdcInsn(s); callStr(mv, "strV")
        case other           => throw new Unsupported(s"lit:$other")
      case Term.Global(g) =>
        mv.visitLdcInsn(g); callStr(mv, "global")
      case Term.Local(i) =>
        mv.visitVarInsn(Opcodes.ALOAD, ctx.slotOf(i))
      case Term.Seq(ts) =>
        ts.zipWithIndex.foreach { (s, i) =>
          gen(s, ctx)
          if i < ts.length - 1 then mv.visitInsn(Opcodes.POP)
        }
      case Term.If(c, a, b) =>
        gen(c, ctx)
        callVB(mv, "bool")
        val elseL = new Label(); val endL = new Label()
        mv.visitJumpInsn(Opcodes.IFEQ, elseL)
        gen(a, ctx); mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(elseL); gen(b, ctx); mv.visitLabel(endL)
      case Term.Let(rhs, body) =>
        rhs.foreach { r =>
          gen(r, ctx)
          val slot = ctx.push()
          mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
        gen(body, ctx)
        ctx.pop(rhs.length)
      case Term.Prim(op, args) =>
        args.length match
          case 0 => mv.visitLdcInsn(op); callP(mv, "prim0", 0)
          case 1 => mv.visitLdcInsn(op); gen(args(0), ctx); callP(mv, "prim1", 1)
          case 2 => mv.visitLdcInsn(op); gen(args(0), ctx); gen(args(1), ctx); callP(mv, "prim2", 2)
          case 3 => mv.visitLdcInsn(op); gen(args(0), ctx); gen(args(1), ctx); gen(args(2), ctx); callP(mv, "prim3", 3)
          case n =>
            mv.visitLdcInsn(op); genArray(args, ctx); callArr(mv, "primN")
      case Term.App(f, args) =>
        gen(f, ctx); genArray(args, ctx); callApp(mv)
      case Term.Ctor(tag, fields) =>
        mv.visitLdcInsn(tag); genArray(fields, ctx); callCtorArr(mv)
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

  // ── static forwarders on Emit's companion-object: Scala objects expose
  //    STATIC forwarders on the companion class for public methods ✓
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

  /** defineClass + invoke entry(). */
  private final class GenLoader(parent: ClassLoader) extends ClassLoader(parent):
    def define(name: String, bytes: Array[Byte]): Class[?] =
      defineClass(name, bytes, 0, bytes.length)

  def runEntry(bytes: Array[Byte]): Value =
    val cls = new GenLoader(getClass.getClassLoader).define("ssc.gen.Entry", bytes)
    cls.getMethod("entry").invoke(null).asInstanceOf[Value]
