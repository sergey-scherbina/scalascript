package scalascript.interpreter.vm

import scala.meta.*
import scala.collection.mutable
import scalascript.interpreter.Value
import SscVm.*

/** Compiles a `Value.FunV` whose params are all integer-typed and whose body
 *  is in the supported subset (see docs/vm-jit-spec.md §5) into a [[CompiledFn]].
 *  Returns `None` on any unsupported construct — the caller then falls back to
 *  the tree-walking interpreter, so this can never change semantics.
 *
 *  Supports self-recursion (compiled to a loop in tail position) and calls to
 *  *other* integer functions, including mutual recursion. Callees are resolved
 *  through a caller-supplied `resolve` function and compiled on demand into the
 *  same [[Ctx]], so a cyclic call graph terminates (each function compiled once).
 */
object VmCompiler:

  /** Max supported arity. The VM `CALL` copies `arity` registers; the only cost
   *  of a higher cap is slightly larger frames. */
  final val MaxArity = 8

  private final class Bail extends RuntimeException

  private val intTypes = Set("Int", "Long", "")

  /** A name resolver: given the function currently being compiled and a free
   *  name appearing in its body, return the `FunV` that name refers to (a
   *  sibling/top-level function), or `null` if it is not a compilable function
   *  reference. Self-references are handled by name-match and do NOT go through
   *  this resolver, so it is only consulted for calls to *other* functions. */
  type Resolve = (Value.FunV, String) => Value.FunV | Null

  private val noResolve: Resolve = (_, _) => null

  /** Self-recursion only (no sibling calls). Used by tests/bench. */
  def compile(fn: Value.FunV): Option[CompiledFn] = compile(fn, noResolve)

  /** Compile `fn`, resolving calls to other functions via `resolve`. */
  def compile(fn: Value.FunV, resolve: Resolve): Option[CompiledFn] =
    try Some(new Ctx(resolve).compileFn(fn))
    catch case _: Bail => None

  private def bail(): Nothing = throw new Bail

  private def typeGateOk(fn: Value.FunV): Boolean =
    val typesOk = fn.paramTypes.isEmpty || fn.paramTypes.forall(t => intTypes.contains(t.trim))
    typesOk && fn.usingParams.isEmpty && !fn.defaults.exists(_.isDefined)

  // ── shared compilation context (handles cyclic call graphs) ──────
  private final class Ctx(resolve: Resolve):
    // Shells registered BEFORE their callPool is filled, so a cycle (f→g→f)
    // resolves to the in-progress shell instead of recompiling forever.
    private val building = new java.util.IdentityHashMap[Value.FunV, CompiledFn]()

    def compileFn(fn: Value.FunV): CompiledFn =
      val existing = building.get(fn)
      if existing != null then existing
      else
        if !typeGateOk(fn) then bail()
        val b = new Builder(fn, this)
        b.buildInstructions()
        val shell = new CompiledFn(
          name = fn.name, arity = b.arityOf, numRegs = b.maxRegOf,
          op = b.opArr, a = b.aArr, b = b.bArr, c = b.cArr,
          constPool = b.constArr, callPool = new Array[CompiledFn](b.callees.length)
        )
        building.put(fn, shell)            // register before filling — breaks cycles
        var i = 0
        while i < b.callees.length do
          shell.callPool(i) = compileFn(b.callees(i))
          i += 1
        shell

    def resolveName(owner: Value.FunV, name: String): Value.FunV | Null =
      resolve(owner, name)

  // ── per-function compilation state ───────────────────────────────
  private final class Builder(fn: Value.FunV, ctx: Ctx):
    private val ops   = mutable.ArrayBuffer.empty[Int]
    private val as    = mutable.ArrayBuffer.empty[Int]
    private val bs    = mutable.ArrayBuffer.empty[Int]
    private val cs    = mutable.ArrayBuffer.empty[Int]
    private val consts = mutable.ArrayBuffer.empty[Long]
    private val locals = mutable.HashMap.empty[String, Int]
    private var nextReg = 0
    private var maxReg  = 0

    // Callees referenced by CALL, in slot order; deduped by identity.
    val callees = mutable.ArrayBuffer.empty[Value.FunV]
    private val calleeSlot = new java.util.IdentityHashMap[Value.FunV, Integer]()

    def arityOf: Int  = fn.params.length
    def maxRegOf: Int = maxReg
    def opArr: Array[Int]    = ops.toArray
    def aArr: Array[Int]     = as.toArray
    def bArr: Array[Int]     = bs.toArray
    def cArr: Array[Int]     = cs.toArray
    def constArr: Array[Long] = consts.toArray

    private def slotFor(callee: Value.FunV): Int =
      val s = calleeSlot.get(callee)
      if s != null then s.intValue
      else
        val n = callees.length
        callees += callee
        calleeSlot.put(callee, Integer.valueOf(n))
        n

    private def freshReg(): Int =
      val r = nextReg; nextReg += 1
      if nextReg > maxReg then maxReg = nextReg
      r

    private def freshRegs(n: Int): Int =
      val base = nextReg; nextReg += n
      if nextReg > maxReg then maxReg = nextReg
      base

    private def constSlot(v: Long): Int =
      var i = 0
      while i < consts.length do { if consts(i) == v then return i; i += 1 }
      consts += v; consts.length - 1

    private def emit(op: Int, a: Int, b: Int, c: Int): Int =
      ops += op; as += a; bs += b; cs += c; ops.length - 1

    private def opcodeFor(op: String): Int = op match
      case "+"  => ADD; case "-"  => SUB; case "*" => MUL
      case "/"  => DIV; case "%"  => MOD
      case "<"  => LT;  case "<=" => LE; case ">" => GT; case ">=" => GE
      case "==" => EQ;  case "!=" => NE
      case _    => bail()

    /** Resolve `app.fun` to a compilable callee, or None.
     *  Order mirrors the interpreter: a local/param of that name shadows any
     *  function (and a local holds a Long, not something callable → bail);
     *  then the function's own name (self); then a sibling via the resolver. */
    private def callTarget(app: Term.Apply): Option[Value.FunV] =
      app.fun match
        case n: Term.Name =>
          val nm = n.value
          if locals.contains(nm) then None
          else if fn.name.nonEmpty && nm == fn.name then Some(fn)
          else ctx.resolveName(fn, nm) match
            case f: Value.FunV => Some(f)
            case null          => None
        case _ => None

    // Compile an expression, returning the register holding its Long result.
    private def compileExpr(t: Term): Int = t match
      case Lit.Int(v)  => val r = freshReg(); emit(CONST, r, constSlot(v.toLong), 0); r
      case Lit.Long(v) => val r = freshReg(); emit(CONST, r, constSlot(v), 0); r

      case n: Term.Name =>
        locals.getOrElse(n.value, bail())

      case app: Term.ApplyInfix if app.argClause.values.lengthCompare(1) == 0 =>
        val opc = opcodeFor(app.op.value)
        val lr  = compileExpr(app.lhs)
        val rr  = compileExpr(app.argClause.values.head)
        val d   = freshReg(); emit(opc, d, lr, rr); d

      case t: Term.If =>
        val cr  = compileExpr(t.cond)
        val jf  = emit(JF, cr, -1, 0)            // patch to else-start
        val res = freshReg()
        val tr  = compileExpr(t.thenp); emit(MOVE, res, tr, 0)
        val jmp = emit(JMP, -1, 0, 0)            // patch to end
        bs(jf) = ops.length                      // JF else-target: else-branch starts here
        val er  = compileExpr(t.elsep); emit(MOVE, res, er, 0)
        as(jmp) = ops.length                     // end
        res

      // call to self or another compilable integer function
      case app: Term.Apply =>
        callTarget(app) match
          case Some(callee) =>
            val args = app.argClause.values
            if args.lengthCompare(callee.params.length) != 0 then bail()
            val slot    = slotFor(callee)
            val argBase = freshRegs(args.length)
            var i = 0
            while i < args.length do
              val ar = compileExpr(args(i))
              emit(MOVE, argBase + i, ar, 0)
              i += 1
            val d = freshReg(); emit(CALL, d, argBase, slot); d
          case None => bail()

      case Term.Block(stats) =>
        compileStats(stats)

      case Term.While(cond, body) =>
        val start = ops.length
        val cr    = compileExpr(cond)
        val jf    = emit(JF, cr, -1, 0)
        compileStmt(body)
        emit(JMP, start, 0, 0)
        bs(jf) = ops.length
        val r = freshReg(); emit(CONST, r, constSlot(0L), 0); r  // while ⇒ unit ⇒ 0

      case _ => bail()

    // Compile `t` in TAIL position: every path ends in either a RET or a
    // jump back to instruction 0 (a self-tail-call turned into a loop). This
    // gives constant host-stack depth for self-tail recursion — the same shape
    // the JVM backend produces, and what makes deep `sumTco` not overflow.
    // Tail calls to *other* functions go through compileExpr (a CALL + RET);
    // pathologically deep mutual tail recursion may overflow the host stack and
    // safely fall back to the tree-walker.
    private def compileTail(t: Term): Unit = t match
      case ti: Term.If =>
        val cr = compileExpr(ti.cond)
        val jf = emit(JF, cr, -1, 0)
        compileTail(ti.thenp)        // then-branch self-terminates
        bs(jf) = ops.length
        compileTail(ti.elsep)        // else-branch self-terminates

      case app: Term.Apply if isSelfTailCall(app) =>
        val args = app.argClause.values
        if args.lengthCompare(fn.params.length) != 0 then bail()
        // Evaluate every arg into a temp BEFORE overwriting the param regs,
        // since args (e.g. `acc + n`) read the current params.
        val tmp = new Array[Int](args.length)
        var i = 0
        while i < args.length do { tmp(i) = compileExpr(args(i)); i += 1 }
        i = 0
        while i < args.length do { emit(MOVE, i, tmp(i), 0); i += 1 } // params = r0..r(n-1)
        emit(JMP, 0, 0, 0)           // loop to start

      case Term.Block(stats) =>
        if stats.isEmpty then bail()
        var rest = stats
        while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
        compileTail(rest.head.asInstanceOf[Term])

      case other =>
        val r = compileExpr(other); emit(RET, r, 0, 0)

    private def isSelfTailCall(app: Term.Apply): Boolean =
      fn.name.nonEmpty && (app.fun match
        case n: Term.Name => n.value == fn.name && !locals.contains(n.value)
        case _            => false)

    // A statement whose value may be discarded (loop bodies, non-final stmts).
    private def compileStmt(t: Tree): Unit = t match
      case Defn.Val(_, List(Pat.Var(nm: Term.Name)), _, rhs) =>
        val r = compileExpr(rhs); locals(nm.value) = bindReg(r)
      case Defn.Var.After_4_7_2(_, List(Pat.Var(nm)), _, rhs) =>
        val r = compileExpr(rhs); locals(nm.value) = bindReg(r)
      case Term.Assign(nm: Term.Name, rhs) =>
        val dst = locals.getOrElse(nm.value, bail())
        val r   = compileExpr(rhs); emit(MOVE, dst, r, 0)
      case Term.Block(stats) => compileStats(stats); ()
      case e: Term            => compileExpr(e); ()
      case _                  => bail()

    // A `val x = e` should give `x` a stable home register. compileExpr may have
    // produced its result in a temp; move it into a fresh dedicated slot so later
    // reassignments (var) and reads are consistent.
    private def bindReg(src: Int): Int =
      val home = freshReg(); emit(MOVE, home, src, 0); home

    private def compileStats(stats: List[Stat]): Int =
      if stats.isEmpty then bail()
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileExpr(rest.head.asInstanceOf[Term])

    /** Emit the instruction stream; callee shells are resolved later by [[Ctx]]. */
    def buildInstructions(): Unit =
      val arity = fn.params.length
      if arity < 1 || arity > MaxArity then bail()
      var i = 0
      while i < arity do { locals(fn.params(i)) = i; i += 1 }   // params occupy r0..r(arity-1)
      nextReg = arity; maxReg = arity
      compileTail(fn.body)           // emits RET / loop on every path
