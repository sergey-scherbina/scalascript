package ssc.bytecode

import org.objectweb.asm.{ClassWriter, MethodVisitor, Opcodes, Label, Handle, Type as AsmType}
import ssc.{Term, Const, Value, Program, HandlerDispatchShape, PortableEffects}

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
  private val INTV  = "ssc/Value$IntV"
  private val LCELL = "ssc/Value$LongCellV"
  private val DCELL = "ssc/Value$DoubleCellV"
  private val OBJ   = "java/lang/Object"
  private val GEN   = "ssc/gen/Entry"
  private val ARTIFACT = "ssc/plugin/NativeArtifactRuntime"

  private def isHandlerDispatchRoot(arity: Int, body: Term): Boolean =
    HandlerDispatchShape.isRoot(arity, body)

  private val metafactory = new Handle(
    Opcodes.H_INVOKESTATIC,
    "java/lang/invoke/LambdaMetafactory", "metafactory",
    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
    false)

  private final case class Pending(
      name: String,
      body: Term,
      selfGlobal: String | Null = null,
      selfArity: Int = -1,
      sourceLine: Option[Int] = None,
      localTailTargets: Map[Int, (String, Int)] = Map.empty,
      localFrameArity: Int = -1,
      handlerDispatchRoot: Boolean = false)

  private final class Gen(val cw: ClassWriter, val sourceDebug: Option[JvmSourceDebug]):
    val pending = collection.mutable.Queue.empty[Pending]
    var lamIdx = 0
    def freshLam(): String = { lamIdx += 1; s"lam$$$lamIdx" }
    /** Top-level Lam defs: name → (methodName, arity). Calls to these compile
     *  to DIRECT invokestatic — no global lookup, no ClosV, no Emit.app. */
    val defMethods = collection.mutable.HashMap.empty[String, (String, Int)]
    /** Qualified partial functions must execute through their ClosV wrapper so
     *  the runtime scopes the unforgeable handler-dispatch owner token. */
    val handlerRootDefs = collection.mutable.HashSet.empty[String]
    /** Pending Seq chains: (per-statement method names, statements, tailLast). */
    val chains = collection.mutable.Queue.empty[(Vector[String], List[Term], Boolean, Vector[Option[Int]])]
    /** Pending Let chains: (step names, body name, rhs terms, body, tail). */
    val letChains = collection.mutable.Queue.empty[(Vector[String], String, List[Term], Term, Boolean, Option[Int])]
    /** Top-level def names whose body is provably effect-free (never yields an
     *  Op) — App to these is safe inside an inline-foreach body. */
    val pureDefs = collection.mutable.HashSet[String]()

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
    var sourceLine: Option[Int] = None
    var statementLines: Vector[Option[Int]] = Vector.empty
    /** Environment-relative De Bruijn index -> compiled LetRec target/arity.
     * JVM-slot lets/match binders are subtracted at the call site. */
    var localTailTargets: Map[Int, (String, Int)] = Map.empty
    /** Number of current call arguments at the end of the local LetRec frame. */
    var localFrameArity: Int = -1
    def saveSlots(): List[Int] = { val s = stack.toList; stack.clear(); s }
    def restoreSlots(s: List[Int]): Unit = { stack.clear(); stack ++= s }

  def emitProgram(p: Program): Array[Byte] = emitProgram(p, None)

  def emitProgram(p: Program, sourceDebug: JvmSourceDebug): Array[Byte] =
    emitProgram(p, Some(sourceDebug))

  private def emitProgram(p: Program, sourceDebug: Option[JvmSourceDebug]): Array[Byte] =
    val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GEN, null, OBJ, null)
    sourceDebug.foreach(debug => cw.visitSource(debug.sourceFile, debug.smap))
    val g = new Gen(cw, sourceDebug)

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
        case Term.Lam(ar, body) if lastDefs.get(d.name).contains(i) =>
          g.defMethods(d.name) = (g.freshLam(), ar)
          if isHandlerDispatchRoot(ar, body) then g.handlerRootDefs += d.name
        case _ => ()
    }

    // entry(): compiled entry term with an EMPTY frame
    val entryLines = sourceDebug.toVector.flatMap(debug => debug.entryLines.map(debug.outputLine))
      .map(Some(_))
    emitBody(
      g,
      "entry",
      p.entry,
      paramIsEnv = false,
      sourceLine = entryLines.headOption.flatten,
      statementLines = entryLines)

    // A persisted class is directly executable. Runtime/plugin initialization
    // stays in the core-free native artifact helper; generated code only owns
    // the stable JVM main convention and its own install/entry calls.
    val mainMv = cw.visitMethod(
      Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
      "main",
      "([Ljava/lang/String;)V",
      null,
      null)
    mainMv.visitCode()
    entryLines.headOption.flatten.foreach(line => markLine(mainMv, line))
    mainMv.visitVarInsn(Opcodes.ALOAD, 0)
    mainMv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      ARTIFACT,
      "initialize",
      "([Ljava/lang/String;)V",
      false)
    mainMv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, "install", "()V", false)
    mainMv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, "entry", s"()L$VAL;", false)
    mainMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "unroll", s"(L$VAL;)L$VAL;", false)
    mainMv.visitMethodInsn(
      Opcodes.INVOKESTATIC,
      ARTIFACT,
      "report",
      s"(L$VAL;)V",
      false)
    mainMv.visitInsn(Opcodes.RETURN)
    mainMv.visitMaxs(0, 0)
    mainMv.visitEnd()

    // install(): compiled ClosV for every top-level Lam def (overrides the
    // VM-compiled version in Emit.globalsRef; value defs stay VM-compiled)
    val installMv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "()V", null, null)
    installMv.visitCode()
    // slot 0 = empty frame (value-def bodies may materialize chains)
    installMv.visitInsn(Opcodes.ICONST_0)
    installMv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
    installMv.visitVarInsn(Opcodes.ASTORE, 0)
    p.defs.zipWithIndex.foreach { (d, i) =>
      d.body match
        case _: Term.Lam => ()
        case valueBody =>
          // VALUE defs (cells, constants — incl. import-merged @var cells)
          // must be EVALUATED at startup: the VM runs CDefs in order; the
          // bytecode lane does it here, in def order, before entry.
          installMv.visitLdcInsn(d.name)
          val vctx = new Ctx(installMv, g)
          sourceLineFor(g, d.name).foreach(line => markLine(vctx, line))
          gen(valueBody, vctx)
          installMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "registerGlobal", s"(Ljava/lang/String;L$VAL;)V", false)
      d.body match
        case Term.Lam(ar, body) if lastDefs.get(d.name).contains(i) =>
          val (m, _) = g.defMethods(d.name)
          g.pending.enqueue(Pending(
            m,
            body,
            selfGlobal = d.name,
            selfArity = ar,
            sourceLine = sourceLineFor(g, d.name),
            handlerDispatchRoot = isHandlerDispatchRoot(ar, body)))
          installMv.visitLdcInsn(d.name)
          installMv.visitLdcInsn(ar)
          installMv.visitInvokeDynamicInsn("call", s"()L$LAMFN;", metafactory,
            AsmType.getType(s"([L$VAL;)L$VAL;"),
            new Handle(Opcodes.H_INVOKESTATIC, GEN, m, s"([L$VAL;)L$VAL;", false),
            AsmType.getType(s"([L$VAL;)L$VAL;"))
          installMv.visitInsn(Opcodes.ICONST_0)
          installMv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
          val closMethod = if isHandlerDispatchRoot(ar, body) then "handlerClos" else "clos"
          installMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, closMethod,
            s"(IL$LAMFN;[L$VAL;)L$VAL;", false)
          installMv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "registerGlobal", s"(Ljava/lang/String;L$VAL;)V", false)
        case _ => () // non-lam defs remain VM-compiled in globalsRef
    }
    installMv.visitInsn(Opcodes.RETURN)
    installMv.visitMaxs(0, 0); installMv.visitEnd()

    // Fixpoint: a top-level def is PURE if its Lam body is effect-free given the
    // currently-known pure defs (allows mutual recursion among pure defs). Lets
    // an inline-foreach body call pure defs — e.g. `shapes.foreach(s => total =
    // total + area(s))` where `area` is a pure match+arith.
    val lamBodies = p.defs.collect {
      case d if d.body.isInstanceOf[Term.Lam] => d.name -> d.body.asInstanceOf[Term.Lam].body
    }.toMap  // last-wins, mirrors runtime def shadowing
    var pureChanged = true
    while pureChanged do
      pureChanged = false
      lamBodies.foreach { (name, body) =>
        if !g.pureDefs.contains(name) && pureNoEffect(body, g.pureDefs) then
          g.pureDefs += name; pureChanged = true
      }

    // drain pending lam bodies and seq chains (each may enqueue more)
    while g.pending.nonEmpty || g.chains.nonEmpty || g.letChains.nonEmpty do
      while g.pending.nonEmpty do
        val pnd = g.pending.dequeue()
        emitBody(
          g,
          pnd.name,
          pnd.body,
          paramIsEnv = true,
          selfGlobal = pnd.selfGlobal,
          selfArity = pnd.selfArity,
          sourceLine = pnd.sourceLine,
          localTailTargets = pnd.localTailTargets,
          localFrameArity = pnd.localFrameArity,
          handlerDispatchRoot = pnd.handlerDispatchRoot)
      while g.chains.nonEmpty do
        val (names, ts, tailLast, lines) = g.chains.dequeue()
        emitChain(g, names, ts, tailLast, lines)
      while g.letChains.nonEmpty do
        val (steps, bodyName, rhs, body, tl, line) = g.letChains.dequeue()
        emitLetChain(g, steps, bodyName, rhs, body, tl, line)

    cw.visitEnd()
    cw.toByteArray

  /** Cutoff-based De Bruijn shift (for the Match-scrutinee Let rewrite). */
  private def shift(t: Term, amount: Int, cutoff: Int): Term = t match
    case Term.Local(i) => if i >= cutoff then Term.Local(i + amount) else t
    case Term.Lam(ar, b) => Term.Lam(ar, shift(b, amount, cutoff + ar))
    case Term.App(f, args) => Term.App(shift(f, amount, cutoff), args.map(shift(_, amount, cutoff)))
    case Term.Let(rhs, b) =>
      val (shiftedRhs, _) = rhs.foldLeft((List.empty[Term], cutoff)) { case ((acc, c), r) =>
        (acc :+ shift(r, amount, c), c + 1)
      }
      Term.Let(shiftedRhs, shift(b, amount, cutoff + rhs.length))
    case Term.LetRec(lams, b) =>
      val c2 = cutoff + lams.length
      Term.LetRec(lams.map(shift(_, amount, c2)), shift(b, amount, c2))
    case Term.If(c, a, b) => Term.If(shift(c, amount, cutoff), shift(a, amount, cutoff), shift(b, amount, cutoff))
    case Term.Ctor(tag, fs) => Term.Ctor(tag, fs.map(shift(_, amount, cutoff)))
    case Term.Match(s, arms, d) =>
      Term.Match(shift(s, amount, cutoff),
        arms.map(a => a.copy(body = shift(a.body, amount, cutoff + a.arity))),
        d.map(shift(_, amount, cutoff)))
    case Term.Prim(op, args) => Term.Prim(op, args.map(shift(_, amount, cutoff)))
    case Term.Seq(ts) => Term.Seq(ts.map(shift(_, amount, cutoff)))
    case Term.While(c, b) => Term.While(shift(c, amount, cutoff), shift(b, amount, cutoff))
    case _ => t // Lit, Global

  /** Conservative EFFECT-FREE classifier for inline-foreach bodies: true only
   *  when evaluating the term cannot produce an effect Op. Excludes App and the
   *  effect/method dispatch prims (which can yield Ops); allows arith, var-cell
   *  reads/writes, array ops, control flow, and leaves. Mirrors the intent of
   *  the VM's tryFC declining effectful bodies. */
  private def pureNoEffect(t: Term, pureDefs: collection.Set[String]): Boolean = t match
    case Term.Lit(_) | Term.Local(_) | Term.Global(_) => true
    case Term.Prim(op, args) =>
      val okOp = op == "__arith__" || op == "__isTag__" || op == "fieldAt" ||
        op.startsWith("cell.") || op.startsWith("lcell.") || op.startsWith("dcell.") ||
        op == "arr.get" || op == "arr.set" || op == "unitV"
      okOp && args.forall(pureNoEffect(_, pureDefs))
    case Term.If(c, a, b) => pureNoEffect(c, pureDefs) && pureNoEffect(a, pureDefs) && pureNoEffect(b, pureDefs)
    case Term.Seq(ts)     => ts.forall(pureNoEffect(_, pureDefs))
    case Term.Let(rhs, b) => rhs.forall(pureNoEffect(_, pureDefs)) && pureNoEffect(b, pureDefs)
    case Term.Ctor(_, fs) => fs.forall(pureNoEffect(_, pureDefs))
    case Term.Match(s, arms, d) =>
      pureNoEffect(s, pureDefs) && arms.forall(a => pureNoEffect(a.body, pureDefs)) && d.forall(pureNoEffect(_, pureDefs))
    // App to a provably-pure top-level def is effect-free (e.g. `area(s)` in a
    // foreach body); other calls stay conservatively effectful.
    case Term.App(Term.Global(g), args) => pureDefs.contains(g) && args.forall(pureNoEffect(_, pureDefs))
    case _ => false // other App, method/effect dispatch, Lam, LetRec, While — conservatively effectful

  /** Conservative may-produce-Op classifier (mirrors OpAnf.mayOp): only terms
   *  that can reach an App or a method/effect dispatch can yield an Op. */
  private def mayOp(t: Term): Boolean = t match
    case Term.App(_, _) => true
    case Term.Prim(op, args) =>
      ssc.Compiler.primitiveMayProduceAutoThreadOp(op) || args.exists(mayOp)
    case Term.If(c, a, b)    => mayOp(c) || mayOp(a) || mayOp(b)
    case Term.Seq(ts)        => ts.exists(mayOp)
    case Term.Let(r, b)      => r.exists(mayOp) || mayOp(b)
    case Term.Match(s, arms, d) => mayOp(s) || arms.exists(a => mayOp(a.body)) || d.exists(mayOp)
    case Term.Ctor(_, fs)    => fs.exists(mayOp)
    case _ => false // Lit, Local, Global, Lam, LetRec, While

  /** Let chain: step i binds rhs_i (env has bindings 0..i-1 appended). */
  private def sourceLineFor(g: Gen, name: String): Option[Int] =
    g.sourceDebug.flatMap(debug => debug.definitionLines.get(name).map(debug.outputLine))

  private def markLine(mv: MethodVisitor, line: Int): Unit =
    val label = new Label()
    mv.visitLabel(label)
    mv.visitLineNumber(line, label)

  private def markLine(ctx: Ctx, line: Int): Unit =
    markLine(ctx.mv, line)
    ctx.sourceLine = Some(line)

  private def consumeStatementLines(ctx: Ctx, count: Int): Vector[Option[Int]] =
    val lines = ctx.statementLines
    ctx.statementLines = Vector.empty
    if lines.length == count then lines
    else if lines.length > count then lines.takeRight(count)
    else Vector.fill(count - lines.length)(ctx.sourceLine) ++ lines

  private def emitLetChain(g: Gen, steps: Vector[String], bodyName: String,
                           rhs: List[Term], body: Term, tl: Boolean,
                           sourceLine: Option[Int]): Unit =
    rhs.zipWithIndex.foreach { (r, i) =>
      val mv = g.cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, steps(i), s"([L$VAL;)L$VAL;", null, null)
      mv.visitCode()
      val ctx = new Ctx(mv, g)
      sourceLine.foreach(line => markLine(ctx, line))
      gen(r, ctx)
      val next = if i == rhs.length - 1 then bodyName else steps(i + 1)
      mv.visitInsn(Opcodes.DUP)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "isOp", s"(L$VAL;)Z", false)
      val contL = new Label()
      mv.visitJumpInsn(Opcodes.IFEQ, contL)
      emitLamFnRef(ctx, next)
      mv.visitVarInsn(Opcodes.ALOAD, 0)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "letThread", s"(L$VAL;L$LAMFN;[L$VAL;)L$VAL;", false)
      mv.visitInsn(Opcodes.ARETURN)
      mv.visitLabel(contL)
      // bind: env :+ value → next step
      val vSlot = ctx.nextSlot; ctx.nextSlot += 1
      mv.visitVarInsn(Opcodes.ASTORE, vSlot)
      mv.visitVarInsn(Opcodes.ALOAD, 0)
      mv.visitVarInsn(Opcodes.ALOAD, vSlot)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "extend1", s"([L$VAL;L$VAL;)[L$VAL;", false)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, next, s"([L$VAL;)L$VAL;", false)
      mv.visitInsn(Opcodes.ARETURN)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }
    val mv = g.cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, bodyName, s"([L$VAL;)L$VAL;", null, null)
    mv.visitCode()
    val ctx = new Ctx(mv, g)
    sourceLine.foreach(line => markLine(ctx, line))
    gen(body, ctx, tail = tl)
    mv.visitInsn(Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()

  /** One method per Seq statement: value checked for an un-handled Op at the
   *  boundary — fast path calls the next chain method directly. */
  private def emitChain(
      g: Gen,
      names: Vector[String],
      ts: List[Term],
      tailLast: Boolean,
      sourceLines: Vector[Option[Int]]): Unit =
    ts.zipWithIndex.foreach { (stmt, i) =>
      val last = i == ts.length - 1
      val mv = g.cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, names(i), s"([L$VAL;)L$VAL;", null, null)
      mv.visitCode()
      val ctx = new Ctx(mv, g)
      sourceLines.lift(i).flatten.foreach(line => markLine(ctx, line))
      gen(stmt, ctx, tail = tailLast && last)
      if !last then
        mv.visitInsn(Opcodes.DUP)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "isOp", s"(L$VAL;)Z", false)
        val contL = new Label()
        mv.visitJumpInsn(Opcodes.IFEQ, contL)
        // op path: thread the rest of the chain as the continuation
        emitLamFnRef(ctx, names(i + 1))
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "seqThread", s"(L$VAL;L$LAMFN;[L$VAL;)L$VAL;", false)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitLabel(contL)
        mv.visitInsn(Opcodes.POP)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, names(i + 1), s"([L$VAL;)L$VAL;", false)
      mv.visitInsn(Opcodes.ARETURN)
      mv.visitMaxs(0, 0)
      mv.visitEnd()
    }

  private def emitBody(g: Gen, name: String, body: Term, paramIsEnv: Boolean,
                       selfGlobal: String | Null = null, selfArity: Int = -1,
                       sourceLine: Option[Int] = None,
                       statementLines: Vector[Option[Int]] = Vector.empty,
                       localTailTargets: Map[Int, (String, Int)] = Map.empty,
                       localFrameArity: Int = -1,
                       handlerDispatchRoot: Boolean = false): Unit =
    val desc = if paramIsEnv then s"([L$VAL;)L$VAL;" else s"()L$VAL;"
    val mv = g.cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null)
    mv.visitCode()
    val ctx = new Ctx(mv, g)
    ctx.selfGlobal = if handlerDispatchRoot then null else selfGlobal
    ctx.selfArity = selfArity
    ctx.statementLines = statementLines
    ctx.localTailTargets = localTailTargets
    ctx.localFrameArity = localFrameArity
    if !paramIsEnv then
      // no env param: slot 0 hosts an empty frame for uniformity
      mv.visitInsn(Opcodes.ICONST_0)
      mv.visitTypeInsn(Opcodes.ANEWARRAY, VAL)
      mv.visitVarInsn(Opcodes.ASTORE, 0)
    val paramLongName =
      if paramIsEnv && !handlerDispatchRoot && selfGlobal != null && selfArity >= 1 &&
          canParamLong(body, selfGlobal.nn, selfArity) then
        val ln = s"${name}$$long"
        emitParamLongMethod(g, ln, body, selfGlobal.nn, selfArity, sourceLine)
        Some(ln)
      else None
    val genericStart = new Label()
    paramLongName.foreach { ln =>
      // Fast path only if EVERY param is an IntV; else fall to the generic
      // (boxed) body. Unbox params in $long slot order (param position k =
      // De Bruijn arity-1-k → method slot 2k), then call $long(J*arity)J.
      (0 until selfArity).foreach { i =>
        loadEnvArgValue(mv, i)
        mv.visitTypeInsn(Opcodes.INSTANCEOF, INTV)
        mv.visitJumpInsn(Opcodes.IFEQ, genericStart)
      }
      (0 until selfArity).foreach { k =>
        loadEnvArgLong(mv, selfArity - 1 - k)
      }
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, ln, "(" + ("J" * selfArity) + ")J", false)
      callJ(mv, "intV")
      mv.visitInsn(Opcodes.ARETURN)
    }
    mv.visitLabel(genericStart)
    // self-tail-calls jump here with a rebound frame in slot 0
    val startL = new Label()
    mv.visitLabel(startL)
    ctx.startLabel = startL
    sourceLine.foreach(line => markLine(ctx, line))
    gen(body, ctx, tail = true, handlerDispatchRoot = handlerDispatchRoot)
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

  private def loadLocalValue(i: Int, ctx: Ctx): Unit =
    val mv = ctx.mv
    if i < ctx.slotDepth then
      mv.visitVarInsn(Opcodes.ALOAD, ctx.slotFor(i))
    else
      loadEnv(ctx)
      mv.visitInsn(Opcodes.DUP)
      mv.visitInsn(Opcodes.ARRAYLENGTH)
      mv.visitLdcInsn(1 + (i - ctx.slotDepth))
      mv.visitInsn(Opcodes.ISUB)
      mv.visitInsn(Opcodes.AALOAD)

  private def longArithOpcode(op: String): Int = op match
    case "+" => Opcodes.LADD
    case "-" => Opcodes.LSUB
    case "*" => Opcodes.LMUL
    case "/" => Opcodes.LDIV
    case "%" => Opcodes.LREM
    case _   => throw new Unsupported(s"long-arith:$op")

  private def isLongCmp(op: String): Boolean =
    op == "<" || op == "<=" || op == ">" || op == ">=" || op == "==" || op == "!="

  private def canLong(t: Term): Boolean = t match
    case Term.Lit(Const.CInt(_)) => true
    case Term.Prim("lcell.get", List(Term.Local(_))) => true
    case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
        if op.length == 1 && "+-*/%".contains(op) =>
      canLong(a) && canLong(b)
    case _ => false

  private def genLong(t: Term, ctx: Ctx): Unit =
    val mv = ctx.mv
    t match
      case Term.Lit(Const.CInt(n)) =>
        mv.visitLdcInsn(n)
      case Term.Prim("lcell.get", List(Term.Local(i))) =>
        loadLocalValue(i, ctx)
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCELL)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LCELL, "v", "()J", false)
      case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
          if op.length == 1 && "+-*/%".contains(op) && canLong(a) && canLong(b) =>
        genLong(a, ctx)
        genLong(b, ctx)
        mv.visitInsn(longArithOpcode(op))
      case other =>
        throw new Unsupported(s"long:${other.getClass.getSimpleName}")

  private def genLongCmpFalse(op: String, a: Term, b: Term, ctx: Ctx, falseLabel: Label): Boolean =
    if !isLongCmp(op) || !canLong(a) || !canLong(b) then false
    else
      val mv = ctx.mv
      genLong(a, ctx)
      genLong(b, ctx)
      mv.visitInsn(Opcodes.LCMP)
      val jump = op match
        case "<"  => Opcodes.IFGE
        case "<=" => Opcodes.IFGT
        case ">"  => Opcodes.IFLE
        case ">=" => Opcodes.IFLT
        case "==" => Opcodes.IFNE
        case "!=" => Opcodes.IFEQ
      mv.visitJumpInsn(jump, falseLabel)
      true

  private def genBoolBranchFalse(t: Term, ctx: Ctx, falseLabel: Label): Boolean = t match
    case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b)) =>
      // Long attempt emits nothing when inapplicable (guard short-circuits before emit),
      // so `||` safely falls through to the Double (dcell) comparison path.
      genLongCmpFalse(op, a, b, ctx, falseLabel) || genDoubleCmpFalse(op, a, b, ctx, falseLabel)
    case _ => false

  private def genLongCmpValue(op: String, a: Term, b: Term, ctx: Ctx): Boolean =
    if !isLongCmp(op) || !canLong(a) || !canLong(b) then false
    else
      val mv = ctx.mv
      val falseL = new Label()
      val endL = new Label()
      genLongCmpFalse(op, a, b, ctx, falseL)
      mv.visitInsn(Opcodes.ICONST_1)
      mv.visitJumpInsn(Opcodes.GOTO, endL)
      mv.visitLabel(falseL)
      mv.visitInsn(Opcodes.ICONST_0)
      mv.visitLabel(endL)
      callZ(mv, "boolV")
      true

  // ── Unboxed Double (dcell) machinery — the twin of the Long/lcell path above.
  // Restricted to the ops the VM's float fast path (arithFast) computes, so the two
  // lanes stay bit-identical: + - * / (arith) and < <= > >= (compare). % / == / != on
  // floats fall to the boxed Emit.arith here (as they do in the VM).
  private def doubleArithOpcode(op: String): Int = op match
    case "+" => Opcodes.DADD
    case "-" => Opcodes.DSUB
    case "*" => Opcodes.DMUL
    case "/" => Opcodes.DDIV
    case _   => throw new Unsupported(s"double-arith:$op")

  private def isDoubleCmp(op: String): Boolean =
    op == "<" || op == "<=" || op == ">" || op == ">="

  private def canDouble(t: Term): Boolean = t match
    case Term.Lit(Const.CFloat(_)) => true
    case Term.Prim("dcell.get", List(Term.Local(_))) => true
    case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
        if op.length == 1 && "+-*/".contains(op) =>
      canDouble(a) && canDouble(b)
    case _ => false

  private def genDouble(t: Term, ctx: Ctx): Unit =
    val mv = ctx.mv
    t match
      case Term.Lit(Const.CFloat(d)) =>
        mv.visitLdcInsn(d)
      case Term.Prim("dcell.get", List(Term.Local(i))) =>
        loadLocalValue(i, ctx)
        mv.visitTypeInsn(Opcodes.CHECKCAST, DCELL)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DCELL, "v", "()D", false)
      case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
          if op.length == 1 && "+-*/".contains(op) && canDouble(a) && canDouble(b) =>
        genDouble(a, ctx)
        genDouble(b, ctx)
        mv.visitInsn(doubleArithOpcode(op))
      case other =>
        throw new Unsupported(s"double:${other.getClass.getSimpleName}")

  private def genDoubleCmpFalse(op: String, a: Term, b: Term, ctx: Ctx, falseLabel: Label): Boolean =
    if !isDoubleCmp(op) || !canDouble(a) || !canDouble(b) then false
    else
      val mv = ctx.mv
      genDouble(a, ctx)
      genDouble(b, ctx)
      // NaN-correct match to the VM (`x < y` is false for NaN): DCMPG for < <= (NaN→+1),
      // DCMPL for > >= (NaN→-1) — the javac convention. Same IF* jump table as LCMP.
      mv.visitInsn(if op == "<" || op == "<=" then Opcodes.DCMPG else Opcodes.DCMPL)
      val jump = op match
        case "<"  => Opcodes.IFGE
        case "<=" => Opcodes.IFGT
        case ">"  => Opcodes.IFLE
        case ">=" => Opcodes.IFLT
      mv.visitJumpInsn(jump, falseLabel)
      true

  private def genDoubleCmpValue(op: String, a: Term, b: Term, ctx: Ctx): Boolean =
    if !isDoubleCmp(op) || !canDouble(a) || !canDouble(b) then false
    else
      val mv = ctx.mv
      val falseL = new Label()
      val endL = new Label()
      genDoubleCmpFalse(op, a, b, ctx, falseL)
      mv.visitInsn(Opcodes.ICONST_1)
      mv.visitJumpInsn(Opcodes.GOTO, endL)
      mv.visitLabel(falseL)
      mv.visitInsn(Opcodes.ICONST_0)
      mv.visitLabel(endL)
      callZ(mv, "boolV")
      true

  private def canParamLong(t: Term, selfName: String, arity: Int): Boolean = t match
    case Term.Lit(Const.CInt(_)) => true
    case Term.Local(i) if i >= 0 && i < arity => true
    case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
        if op.length == 1 && "+-*/%".contains(op) =>
      canParamLong(a, selfName, arity) && canParamLong(b, selfName, arity)
    case Term.If(c, a, b) =>
      canParamLongCond(c, selfName, arity) &&
        canParamLong(a, selfName, arity) &&
        canParamLong(b, selfName, arity)
    case Term.App(Term.Global(name), args) if name == selfName && args.length == arity =>
      args.forall(canParamLong(_, selfName, arity))
    case _ => false

  private def canParamLongCond(t: Term, selfName: String, arity: Int): Boolean = t match
    case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b)) if isLongCmp(op) =>
      canParamLong(a, selfName, arity) && canParamLong(b, selfName, arity)
    case _ => false

  private def loadEnvArgValue(mv: MethodVisitor, deBruijn: Int): Unit =
    mv.visitVarInsn(Opcodes.ALOAD, 0)
    mv.visitInsn(Opcodes.DUP)
    mv.visitInsn(Opcodes.ARRAYLENGTH)
    mv.visitLdcInsn(1 + deBruijn)
    mv.visitInsn(Opcodes.ISUB)
    mv.visitInsn(Opcodes.AALOAD)

  private def loadEnvArgLong(mv: MethodVisitor, deBruijn: Int): Unit =
    loadEnvArgValue(mv, deBruijn)
    mv.visitTypeInsn(Opcodes.CHECKCAST, INTV)
    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INTV, "n", "()J", false)

  private def longParamSlot(deBruijn: Int, arity: Int): Int =
    (arity - 1 - deBruijn) * 2

  private def emitParamLong(t: Term, mv: MethodVisitor, selfName: String, arity: Int, longName: String, startL: Label, tail: Boolean): Unit =
    t match
      case Term.Lit(Const.CInt(n)) =>
        mv.visitLdcInsn(n)
      case Term.Local(i) if i >= 0 && i < arity =>
        mv.visitVarInsn(Opcodes.LLOAD, longParamSlot(i, arity))
      case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b))
          if op.length == 1 && "+-*/%".contains(op) =>
        emitParamLong(a, mv, selfName, arity, longName, startL, tail = false)
        emitParamLong(b, mv, selfName, arity, longName, startL, tail = false)
        mv.visitInsn(longArithOpcode(op))
      case Term.If(c, a, b) =>
        val elseL = new Label()
        val endL = new Label()
        emitParamLongCondFalse(c, mv, selfName, arity, longName, startL, elseL)
        emitParamLong(a, mv, selfName, arity, longName, startL, tail)
        mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(elseL)
        emitParamLong(b, mv, selfName, arity, longName, startL, tail)
        mv.visitLabel(endL)
      case Term.App(Term.Global(name), args) if name == selfName && args.length == arity =>
        if tail then
          // SELF-TAIL as a LOOP: push all new arg values (they read the CURRENT
          // param slots, so no clobber), then store them back into the param
          // slots and GOTO the method start. args(k) is param position k → slot
          // longParamSlot(arity-1-k, arity) == 2*k; store top-of-stack first.
          args.foreach(arg => emitParamLong(arg, mv, selfName, arity, longName, startL, tail = false))
          (arity - 1 to 0 by -1).foreach { k =>
            mv.visitVarInsn(Opcodes.LSTORE, k * 2)
          }
          mv.visitJumpInsn(Opcodes.GOTO, startL)
          mv.visitLdcInsn(0L) // unreachable value for the verifier's LRETURN stack shape
        else
          args.foreach(arg => emitParamLong(arg, mv, selfName, arity, longName, startL, tail = false))
          mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, longName, "(" + ("J" * arity) + ")J", false)
      case other =>
        throw new Unsupported(s"param-long:${other.getClass.getSimpleName}")

  private def emitParamLongCondFalse(
      t: Term,
      mv: MethodVisitor,
      selfName: String,
      arity: Int,
      longName: String,
      startL: Label,
      falseLabel: Label
  ): Unit =
    t match
      case Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)), a, b)) if isLongCmp(op) =>
        emitParamLong(a, mv, selfName, arity, longName, startL, tail = false)
        emitParamLong(b, mv, selfName, arity, longName, startL, tail = false)
        mv.visitInsn(Opcodes.LCMP)
        val jump = op match
          case "<"  => Opcodes.IFGE
          case "<=" => Opcodes.IFGT
          case ">"  => Opcodes.IFLE
          case ">=" => Opcodes.IFLT
          case "==" => Opcodes.IFNE
          case "!=" => Opcodes.IFEQ
        mv.visitJumpInsn(jump, falseLabel)
      case other =>
        throw new Unsupported(s"param-long-cond:${other.getClass.getSimpleName}")

  private def emitParamLongMethod(
      g: Gen,
      name: String,
      body: Term,
      selfName: String,
      arity: Int,
      sourceLine: Option[Int]): Unit =
    val desc = "(" + ("J" * arity) + ")J"
    val mv = g.cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, name, desc, null, null)
    mv.visitCode()
    // A self-tail call jumps HERE with the param slots rebound — a constant-
    // stack loop over the unboxed Long params. Non-tail self-calls use a real
    // recursive invokestatic so expressions such as fib(n - 1) + fib(n - 2)
    // still leave a value on the operand stack.
    val startL = new Label()
    mv.visitLabel(startL)
    sourceLine.foreach(line => markLine(mv, line))
    emitParamLong(body, mv, selfName, arity, name, startL, tail = true)
    mv.visitInsn(Opcodes.LRETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()

  private def gen(
      t: Term,
      ctx: Ctx,
      tail: Boolean = false,
      handlerDispatchRoot: Boolean = false
  ): Unit =
    val mv = ctx.mv
    t match
      case Term.Lit(c) => c match
        case Const.CUnit     => call0(mv, "unitV")
        case Const.CBool(b)  => mv.visitInsn(if b then Opcodes.ICONST_1 else Opcodes.ICONST_0); callZ(mv, "boolV")
        case Const.CInt(n)   => mv.visitLdcInsn(n); callJ(mv, "intV")
        case Const.CFloat(d) => mv.visitLdcInsn(d); callD(mv, "floatV")
        case Const.CStr(s)   => mv.visitLdcInsn(s); callStr(mv, "strV")
        // BigInt / byte-vector literals: cannot `ldc` the object, so emit a
        // decimal / base64 String and reconstruct via Emit (was a hard Unsupported).
        case Const.CBig(n)   => mv.visitLdcInsn(n.toString); callStr(mv, "bigVStr")
        case Const.CBytes(b) =>
          mv.visitLdcInsn(java.util.Base64.getEncoder.encodeToString(b.toArray))
          callStr(mv, "bytesVB64")
      case Term.Global(gname) =>
        mv.visitLdcInsn(gname); callStr(mv, "global")
      case Term.Local(i) =>
        loadLocalValue(i, ctx)
      case Term.Lam(ar, body) =>
        val m = ctx.g.freshLam()
        val handlerRoot = isHandlerDispatchRoot(ar, body)
        ctx.g.pending.enqueue(Pending(m, body, sourceLine = ctx.sourceLine,
          handlerDispatchRoot = handlerRoot))
        mv.visitLdcInsn(ar)
        emitLamFnRef(ctx, m)
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT,
          if handlerRoot then "handlerClos" else "clos",
          s"(IL$LAMFN;[L$VAL;)L$VAL;", false)
      case Term.Seq(Nil) =>
        call0(mv, "unitV")
      case Term.Seq(ts) if ts.length == 1 =>
        consumeStatementLines(ctx, 1).headOption.flatten.foreach(line => markLine(ctx, line))
        gen(ts.head, ctx, tail)
      case Term.Seq(ts) if !ts.exists(mayOp) =>
        // PURE sequence (no statement can yield an Op): emit statements INLINE
        // in this method — no chain methods and, critically, no per-iteration
        // emitCapture env materialisation. This is the hot path for while-loop
        // bodies (`sum = sum + i; i = i + 1`), which the effect-threading chain
        // below made ~Nx slower (an env array alloc + 2 invokestatic each iter).
        val lines = consumeStatementLines(ctx, ts.length)
        ts.zipWithIndex.foreach { (s, i) =>
          val last = i == ts.length - 1
          lines.lift(i).flatten.foreach(line => markLine(ctx, line))
          gen(s, ctx, tail && last)
          if !last then mv.visitInsn(Opcodes.POP)
        }
      case Term.Seq(ts) =>
        // STATEMENT-POSITION EFFECT THREADING (VM seqThreadOp mirror): the
        // sequence compiles as a chain of per-statement methods over a
        // MATERIALIZED frame (safe: vars are shared cell objects). At each
        // boundary an isOp check either continues the chain directly (JIT
        // inlines the static calls back) or re-emerges the Op with the rest
        // of the chain as its continuation via Emit.seqThread.
        val chainNames = ts.indices.map(_ => ctx.g.freshLam()).toVector
        val lines = consumeStatementLines(ctx, ts.length)
        ctx.g.chains += ((chainNames, ts, tail, lines))
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, chainNames(0), s"([L$VAL;)L$VAL;", false)
      case Term.If(c, a, b) =>
        val elseL = new Label(); val endL = new Label()
        if !genBoolBranchFalse(c, ctx, elseL) then
          gen(c, ctx); callVB(mv, "bool")
          mv.visitJumpInsn(Opcodes.IFEQ, elseL)
        gen(a, ctx, tail); mv.visitJumpInsn(Opcodes.GOTO, endL)
        mv.visitLabel(elseL); gen(b, ctx, tail); mv.visitLabel(endL)
      case Term.Let(rhs, body) if !rhs.exists(mayOp) =>
        // pure rhs: slot fast path (no allocation, no boundary checks)
        rhs.foreach { r =>
          gen(r, ctx)
          val slot = ctx.push()
          mv.visitVarInsn(Opcodes.ASTORE, slot)
        }
        gen(body, ctx, tail)
        ctx.pop(rhs.length)
      case Term.Let(rhs, body) =>
        // LET-BINDING EFFECT THREADING (VM letThreadOp mirror): OpAnf binds
        // may-Op arguments through Lets and RELIES on this deferral. Compile
        // as a chain over a materialized frame: each step binds by EXTENDING
        // the array (De Bruijn order preserved); an Op rhs re-emerges via
        // Emit.letThread with the rest of the chain as its continuation.
        val stepNames = rhs.indices.map(_ => ctx.g.freshLam()).toVector
        val bodyName  = ctx.g.freshLam()
        ctx.g.letChains += ((stepNames, bodyName, rhs, body, tail, ctx.sourceLine))
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GEN, stepNames(0), s"([L$VAL;)L$VAL;", false)
      case Term.LetRec(lams, body) =>
        // LetRec installs its tied closure frame in local 0 while compiling the
        // expression body. Preserve the caller frame: a surrounding argument
        // or Seq suffix must keep resolving its original De Bruijn locals.
        val callerEnvSlot = ctx.nextSlot
        ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitVarInsn(Opcodes.ASTORE, callerEnvSlot)
        // materialize the current frame, then Emit.letrec(arities, fns, env)
        val entries = lams.map {
          case Term.Lam(ar, b) =>
            val m = ctx.g.freshLam()
            (ar, m, b, isHandlerDispatchRoot(ar, b))
          case other => throw new Unsupported("letrec:non-lam")
        }
        val groupSize = entries.length
        // A local lambda frame is captured ++ group-closures ++ call-args.
        // De Bruijn Local(arity + groupSize - 1 - targetIndex) therefore names
        // a LetRec peer. Preserve the complete group so self and mutual tail
        // calls can return a Bounce instead of recursively invoking Emit.app.
        entries.foreach { case (ar, m, b, handlerRoot) =>
          val targets = entries.zipWithIndex.collect {
            case ((targetArity, targetMethod, _, false), targetIndex) =>
              (ar + groupSize - 1 - targetIndex) -> (targetMethod, targetArity)
          }.toMap
          ctx.g.pending.enqueue(Pending(
            m,
            b,
            sourceLine = ctx.sourceLine,
            localTailTargets = targets,
            localFrameArity = ar,
            handlerDispatchRoot = handlerRoot))
        }
        // arities array
        mv.visitLdcInsn(entries.length)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT)
        entries.zipWithIndex.foreach { case ((ar, _, _, _), i) =>
          mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i); mv.visitLdcInsn(ar); mv.visitInsn(Opcodes.IASTORE)
        }
        // fns array
        mv.visitLdcInsn(entries.length)
        mv.visitTypeInsn(Opcodes.ANEWARRAY, LAMFN)
        entries.zipWithIndex.foreach { case ((_, m, _, _), i) =>
          mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i); emitLamFnRef(ctx, m); mv.visitInsn(Opcodes.AASTORE)
        }
        // canonical handler-root flags
        mv.visitLdcInsn(entries.length)
        mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN)
        entries.zipWithIndex.foreach { case ((_, _, _, handlerRoot), i) =>
          mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn(i)
          mv.visitInsn(if handlerRoot then Opcodes.ICONST_1 else Opcodes.ICONST_0)
          mv.visitInsn(Opcodes.BASTORE)
        }
        emitCapture(ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "letrec",
          s"([I[L$LAMFN;[Z[L$VAL;)[L$VAL;", false)
        // the returned frame becomes the body's env: store into slot 0 of a
        // FRESH context region — simplest correct: overwrite slot 0 after
        // saving? Instead: new env slot + a child ctx view.
        // the returned frame becomes the body's env; the old slots were
        // MATERIALIZED into it, so the body runs with an EMPTY slot stack
        mv.visitVarInsn(Opcodes.ASTORE, 0)
        val savedSlots = ctx.saveSlots()
        val savedSelf = ctx.selfGlobal
        val savedLocalTargets = ctx.localTailTargets
        val savedLocalArity = ctx.localFrameArity
        ctx.selfGlobal = null // slot 0 no longer holds the def's own frame
        ctx.localTailTargets = entries.zipWithIndex.collect {
          case ((targetArity, targetMethod, _, false), targetIndex) =>
            (groupSize - 1 - targetIndex) -> (targetMethod, targetArity)
        }.toMap
        ctx.localFrameArity = 0
        gen(body, ctx, tail)
        ctx.selfGlobal = savedSelf
        ctx.localTailTargets = savedLocalTargets
        ctx.localFrameArity = savedLocalArity
        ctx.restoreSlots(savedSlots)
        // Keep the LetRec result on the operand stack while restoring the
        // method's lexical environment for the surrounding expression.
        mv.visitVarInsn(Opcodes.ALOAD, callerEnvSlot)
        mv.visitVarInsn(Opcodes.ASTORE, 0)
      case Term.While(cond, body) =>
        val startL = new Label(); val endL = new Label()
        mv.visitLabel(startL)
        if !genBoolBranchFalse(cond, ctx, endL) then
          gen(cond, ctx); callVB(mv, "bool")
          mv.visitJumpInsn(Opcodes.IFEQ, endL)
        gen(body, ctx); mv.visitInsn(Opcodes.POP)
        mv.visitJumpInsn(Opcodes.GOTO, startL)
        mv.visitLabel(endL)
        call0(mv, "unitV")
      // INLINE FOREACH: `xs.foreach(x => body)` over a Cons/Nil list with an
      // EFFECT-FREE body compiles to a direct Cons-walk loop with the body
      // inlined (element pushed as a fresh De Bruijn slot = Local(0)) — no
      // callClos, no generic __method__ dispatch per element (VM tryFCAppended
      // analog). Effectful bodies fall through to the runtime foreachConsOp
      // path (which threads Ops); the guard keeps effect semantics correct.
      // Direct .length/.size (no generic __method__ dispatch per call).
      case Term.Prim("__method__", Term.Lit(Const.CStr(lop @ ("length" | "size"))) :: recv :: Nil) =>
        gen(recv, ctx)
        mv.visitLdcInsn(lop)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "len", s"(L$VAL;Ljava/lang/String;)L$VAL;", false)
      case Term.Prim("__method__", Term.Lit(Const.CStr("foreach")) :: recv :: Term.Lam(1, body) :: Nil)
          if pureNoEffect(body, ctx.g.pureDefs) =>
        gen(recv, ctx)
        val listSlot = ctx.nextSlot; ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ASTORE, listSlot)
        val loopStart = new Label(); val loopEnd = new Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "isCons", s"(L$VAL;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd)
        // element = consHead(list) → fresh De Bruijn slot (body's Local(0))
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "consHead", s"(L$VAL;)L$VAL;", false)
        val elemSlot = ctx.push()
        mv.visitVarInsn(Opcodes.ASTORE, elemSlot)
        gen(body, ctx)
        mv.visitInsn(Opcodes.POP)          // foreach discards the body result
        ctx.pop(1)
        // list = consTail(list); loop
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "consTail", s"(L$VAL;)L$VAL;", false)
        mv.visitVarInsn(Opcodes.ASTORE, listSlot)
        mv.visitJumpInsn(Opcodes.GOTO, loopStart)
        mv.visitLabel(loopEnd)
        call0(mv, "unitV")
      // Inline foldLeft Cons-walk (the accumulating sibling of foreach). Shape:
      // xs.foldLeft(z)(f) → __method__("foldLeft", recv, z, Lam(2, body)). The VM runs
      // callClos(f, Array(acc, elem)) and Local(i) indexes the env from the end, so
      // Local(0)=elem, Local(1)=acc — matched here by pushing acc THEN elem. Effectful
      // bodies fall through to the generic __method__ path (VM), same as foreach.
      case Term.Prim("__method__", Term.Lit(Const.CStr("foldLeft")) :: recv :: z :: Term.Lam(2, body) :: Nil)
          if pureNoEffect(body, ctx.g.pureDefs) =>
        gen(recv, ctx)
        val listSlot = ctx.nextSlot; ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ASTORE, listSlot)
        gen(z, ctx)                              // initial accumulator
        val accSlot = ctx.nextSlot; ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ASTORE, accSlot)
        val loopStart = new Label(); val loopEnd = new Label()
        mv.visitLabel(loopStart)
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "isCons", s"(L$VAL;)Z", false)
        mv.visitJumpInsn(Opcodes.IFEQ, loopEnd)
        // De Bruijn: push acc (→ Local(1)) then elem (→ Local(0)), matching callClos(Array(acc,elem))
        mv.visitVarInsn(Opcodes.ALOAD, accSlot)
        val accDb = ctx.push()
        mv.visitVarInsn(Opcodes.ASTORE, accDb)
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "consHead", s"(L$VAL;)L$VAL;", false)
        val elemDb = ctx.push()
        mv.visitVarInsn(Opcodes.ASTORE, elemDb)
        gen(body, ctx)                           // new accumulator on stack
        mv.visitVarInsn(Opcodes.ASTORE, accSlot) // acc = body(acc, elem)
        ctx.pop(2)
        mv.visitVarInsn(Opcodes.ALOAD, listSlot)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "consTail", s"(L$VAL;)L$VAL;", false)
        mv.visitVarInsn(Opcodes.ASTORE, listSlot)
        mv.visitJumpInsn(Opcodes.GOTO, loopStart)
        mv.visitLabel(loopEnd)
        mv.visitVarInsn(Opcodes.ALOAD, accSlot)  // result = accumulator
      case t @ Term.Prim("lcell.get", List(Term.Local(_))) if canLong(t) =>
        genLong(t, ctx)
        callJ(mv, "intV")
      case Term.Prim("lcell.set", List(Term.Local(c), body)) if canLong(body) =>
        loadLocalValue(c, ctx)
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCELL)
        genLong(body, ctx)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LCELL, "v_$eq", "(J)V", false)
        call0(mv, "unitV")
      // Fused accumulator: lcell.set(c, arith(op, lcell.get(c), r)) where r is a boxed
      // element (not statically Long) — the foreach/loop accumulator hot path. Emits a
      // single Emit.lcellAccum (unboxed cell side) instead of box(lcell.get)+arith+prim2.
      case Term.Prim("lcell.set", List(Term.Local(c),
          Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)),
            Term.Prim("lcell.get", List(Term.Local(c2))), r))))
          if c == c2 && op.length == 1 && "+-*/%".contains(op) && !canLong(r) =>
        loadLocalValue(c, ctx)
        mv.visitLdcInsn(op)
        gen(r, ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "lcellAccum",
          s"(L$VAL;Ljava/lang/String;L$VAL;)L$VAL;", false)
      case t @ Term.Prim("dcell.get", List(Term.Local(_))) if canDouble(t) =>
        genDouble(t, ctx)
        callD(mv, "floatV")
      case Term.Prim("dcell.set", List(Term.Local(c), body)) if canDouble(body) =>
        loadLocalValue(c, ctx)
        mv.visitTypeInsn(Opcodes.CHECKCAST, DCELL)
        genDouble(body, ctx)
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DCELL, "v_$eq", "(D)V", false)
        call0(mv, "unitV")
      // Fused double accumulator (twin of the lcell one above): dcell.set(c, arith(op,
      // dcell.get(c), r)) where r is a boxed element (not statically Double).
      case Term.Prim("dcell.set", List(Term.Local(c),
          Term.Prim("__arith__", List(Term.Lit(Const.CStr(op)),
            Term.Prim("dcell.get", List(Term.Local(c2))), r))))
          if c == c2 && op.length == 1 && "+-*/".contains(op) && !canDouble(r) =>
        loadLocalValue(c, ctx)
        mv.visitLdcInsn(op)
        gen(r, ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dcellAccum",
          s"(L$VAL;Ljava/lang/String;L$VAL;)L$VAL;", false)
      case t @ Term.Prim("__arith__", Term.Lit(Const.CStr(aop)) :: a :: b :: Nil) =>
        if genLongCmpValue(aop, a, b, ctx) then ()
        else if canLong(t) then
          genLong(t, ctx)
          callJ(mv, "intV")
        else if genDoubleCmpValue(aop, a, b, ctx) then ()
        else if canDouble(t) then
          genDouble(t, ctx)
          callD(mv, "floatV")
        else
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
      case Term.App(Term.Local(i), args)
          if tail && ctx.localFrameArity >= 0 && i >= ctx.slotDepth &&
            ctx.localTailTargets.get(i - ctx.slotDepth).exists(_._2 == args.length) =>
        val (method, _) = ctx.localTailTargets(i - ctx.slotDepth)
        // LOCAL SELF/MUTUAL TAIL: preserve captured values and the tied LetRec
        // closure group, replace only the current call-argument suffix, and
        // return a Bounce consumed by Emit.unroll. This mirrors Runtime.Call
        // without recursive JVM frames.
        emitLamFnRef(ctx, method)
        loadEnv(ctx)
        mv.visitLdcInsn(ctx.localFrameArity)
        genArray(args, ctx)
        mv.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          EMIT,
          "localRebind",
          s"([L$VAL;I[L$VAL;)[L$VAL;",
          false)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "bounce", s"(L$LAMFN;[L$VAL;)L$VAL;", false)
      case Term.App(Term.Global(gname), args)
          if !ctx.g.handlerRootDefs(gname) &&
            ctx.g.defMethods.get(gname).exists(_._2 == args.length) =>
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
      case Term.Ctor(tag, fields) if tag == "Signal" || tag == "ComputedSignal" =>
        // VM parity: the installed reactive provider wins; bare kernels retain
        // the legacy mutable-cell fallback.
        mv.visitLdcInsn(tag)
        if fields.isEmpty then call0(mv, "unitV") else gen(fields.head, ctx)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "signalNew", s"(Ljava/lang/String;L$VAL;)L$VAL;", false)
      case Term.Ctor(tag, fields) =>
        mv.visitLdcInsn(tag); genArray(fields, ctx); callCtorArr(mv)
      case Term.Match(scrut, arms, default) if mayOp(scrut) =>
        // scrutinee may be an un-handled Op: rewrite to a Let binding so the
        // let-chain threading defers it (arms/default shift under the binder)
        val shiftedArms = arms.map(a => a.copy(body = shift(a.body, 1, a.arity)))
        val shiftedDef  = default.map(d => shift(d, 1, 0))
        gen(Term.Let(List(scrut), Term.Match(Term.Local(0), shiftedArms, shiftedDef)), ctx, tail)
      case Term.Match(scrut, arms, default) =>
        gen(scrut, ctx)
        val scrutSlot = ctx.nextSlot; ctx.nextSlot += 1
        mv.visitVarInsn(Opcodes.ASTORE, scrutSlot)
        val handlerDispatchSlot =
          if handlerDispatchRoot then
            mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "handlerMatchEnter",
              s"(L$VAL;)Z", false)
            val slot = ctx.nextSlot; ctx.nextSlot += 1
            mv.visitVarInsn(Opcodes.ISTORE, slot)
            slot
          else -1
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
          if handlerDispatchRoot then
            mv.visitVarInsn(Opcodes.ILOAD, handlerDispatchSlot)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "handlerMatchSelected", "(Z)V", false)
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
          case Some(d) =>
            if handlerDispatchRoot then
              mv.visitVarInsn(Opcodes.ILOAD, handlerDispatchSlot)
              mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "handlerMatchSelected", "(Z)V", false)
            gen(d, ctx, tail)
          case None =>
            if handlerDispatchRoot then
              mv.visitVarInsn(Opcodes.ILOAD, handlerDispatchSlot)
              mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
              mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataTag", s"(L$VAL;)Ljava/lang/String;", false)
              mv.visitVarInsn(Opcodes.ALOAD, scrutSlot)
              mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "dataArity", s"(L$VAL;)I", false)
              mv.visitMethodInsn(Opcodes.INVOKESTATIC, EMIT, "handlerMatchFailed",
                s"(ZLjava/lang/String;I)L$VAL;", false)
            else
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
    PortableEffects.completeManaged(
      ssc.Emit.unroll(cls.getMethod("entry").invoke(null).asInstanceOf[Value]))
