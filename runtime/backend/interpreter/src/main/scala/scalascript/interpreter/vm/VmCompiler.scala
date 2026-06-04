package scalascript.interpreter.vm

import scala.meta.*
import scala.collection.mutable
import scalascript.interpreter.Value
import SscVm.*
import java.lang as jl

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

  /** Static type of a register's contents. The VM stack is `Long`-typed; for a
   *  `TDouble` register the bits are an IEEE-754 double (see SscVm F* opcodes).
   *  Booleans are represented as `TInt` (0/1), matching the int comparison ops. */
  private enum VmType:
    case TInt, TDouble, TRef
  import VmType.*

  private val intTypes    = Set("Int", "Long", "")
  private val doubleTypes = Set("Double", "Float")

  /** A name resolver: given the function currently being compiled and a free
   *  name appearing in its body, return the `FunV` that name refers to (a
   *  sibling/top-level function), or `null` if it is not a compilable function
   *  reference. Self-references are handled by name-match and do NOT go through
   *  this resolver, so it is only consulted for calls to *other* functions. */
  type Resolve = (Value.FunV, String) => Value.FunV | Null

  private val noResolve: Resolve = (_, _) => null

  /** ADT constructor metadata: given a constructor name, return its field
   *  names + declared field type strings (parallel lists), or null if the name
   *  is not a known case class/enum case. Drives `match` field extraction. */
  type Meta = String => (List[String], List[String]) | Null

  private val noMeta: Meta = _ => null

  /** Self-recursion only (no sibling calls). Used by tests/bench. */
  def compile(fn: Value.FunV): Option[CompiledFn] = compile(fn, noResolve, noMeta)

  /** Compile `fn`, resolving calls to other functions via `resolve`. */
  def compile(fn: Value.FunV, resolve: Resolve): Option[CompiledFn] = compile(fn, resolve, noMeta)

  /** Compile `fn`, resolving sibling calls via `resolve` and ADT constructors
   *  (for `match` field extraction) via `meta`. */
  def compile(fn: Value.FunV, resolve: Resolve, meta: Meta): Option[CompiledFn] =
    try Some(new Ctx(resolve, meta).compileFn(fn))
    catch case _: Bail => None

  private def bail(): Nothing = throw new Bail

  // A param whose declared type is neither numeric nor a known ADT-bearing ref
  // is still allowed through the gate: it becomes a TRef register usable only by
  // match/field/call opcodes (ISTAG safely returns 0 for a non-InstanceV, GETF
  // throws → fallback), so an unsupported use bails inside the Builder instead.
  private def typeGateOk(fn: Value.FunV): Boolean =
    fn.usingParams.isEmpty && !fn.defaults.exists(_.isDefined)

  // ── shared compilation context (handles cyclic call graphs) ──────
  private final class Ctx(resolve: Resolve, meta: Meta):
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
          constPool = b.constArr, callPool = new Array[CompiledFn](b.callees.length),
          retIsDouble = b.retIsDoubleOf, paramIsDouble = b.paramIsDoubleOf,
          paramIsRef = b.paramIsRefOf, strPool = b.strArr
        )
        building.put(fn, shell)            // register before filling — breaks cycles
        var i = 0
        while i < b.callees.length do
          shell.callPool(i) = compileFn(b.callees(i))
          i += 1
        shell

    def resolveName(owner: Value.FunV, name: String): Value.FunV | Null =
      resolve(owner, name)

    def metaFor(ctor: String): (List[String], List[String]) | Null =
      meta(ctor)

  // ── per-function compilation state ───────────────────────────────
  private final class Builder(fn: Value.FunV, ctx: Ctx):
    private val ops   = mutable.ArrayBuffer.empty[Int]
    private val as    = mutable.ArrayBuffer.empty[Int]
    private val bs    = mutable.ArrayBuffer.empty[Int]
    private val cs    = mutable.ArrayBuffer.empty[Int]
    private val consts = mutable.ArrayBuffer.empty[Long]
    private val strs   = mutable.ArrayBuffer.empty[String]
    private val locals = mutable.HashMap.empty[String, Int]
    private var nextReg = 0
    private var maxReg  = 0

    // Static type of each register's contents. Drives int-vs-double opcode
    // selection and int→double promotion. Defaults to TInt for any register
    // not explicitly recorded (the all-integer path needs no entries).
    private val regType = mutable.HashMap.empty[Int, VmType]
    private def typeOf(r: Int): VmType = regType.getOrElse(r, TInt)
    private def setType(r: Int, t: VmType): Unit =
      if t == TInt then regType.remove(r) else regType(r) = t

    /** Map a declared field/param type string to a register domain. Anything
     *  that is not numeric is a ref (an InstanceV, threaded through the ref bank). */
    private def fieldVmType(tpe: String): VmType =
      val tt = tpe.trim
      if doubleTypes.contains(tt) then TDouble
      else if intTypes.contains(tt) then TInt
      else TRef

    // Whether this function operates in the double domain. Decided up-front by a
    // syntactic scan (a Double/Float param, or a double literal anywhere in the
    // body). Used to type self-recursive call results, which would otherwise be
    // circular. The actual return type is re-derived from the RET leaves and must
    // agree (see buildInstructions) — so a misclassification bails, never miswraps.
    private val fnIsDouble: Boolean =
      val paramDouble = fn.paramTypes.exists(t => doubleTypes.contains(t.trim))
      paramDouble || fn.body.collect {
        case _: Lit.Double => ()
      }.nonEmpty

    // Unified type of every value reaching a RET. None until the first leaf.
    private var retType: Option[VmType] = None
    private def unifyRet(t: VmType): Unit =
      if t == TRef then bail()          // ref returns unsupported (RET is Long-typed)
      retType match
        case None             => retType = Some(t)
        case Some(prev) if prev == t => ()
        case _                => bail() // mixed Int/Double returns — fall back
    def retIsDoubleOf: Boolean = retType.contains(TDouble)

    // Callees referenced by CALL, in slot order; deduped by identity.
    val callees = mutable.ArrayBuffer.empty[Value.FunV]
    private val calleeSlot = new java.util.IdentityHashMap[Value.FunV, Integer]()

    def arityOf: Int  = fn.params.length
    def maxRegOf: Int = maxReg
    def paramIsDoubleOf: Array[Boolean] = Array.tabulate(fn.params.length)(i => typeOf(i) == TDouble)
    def paramIsRefOf: Array[Boolean]    = Array.tabulate(fn.params.length)(i => typeOf(i) == TRef)
    def opArr: Array[Int]    = ops.toArray
    def aArr: Array[Int]     = as.toArray
    def bArr: Array[Int]     = bs.toArray
    def cArr: Array[Int]     = cs.toArray
    def constArr: Array[Long] = consts.toArray
    def strArr: Array[String] = strs.toArray

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

    private def strSlot(s: String): Int =
      var i = 0
      while i < strs.length do { if strs(i) == s then return i; i += 1 }
      strs += s; strs.length - 1

    private def emit(op: Int, a: Int, b: Int, c: Int): Int =
      ops += op; as += a; bs += b; cs += c; ops.length - 1

    private def opcodeFor(op: String): Int = op match
      case "+"  => ADD; case "-"  => SUB; case "*" => MUL
      case "/"  => DIV; case "%"  => MOD
      case "<"  => LT;  case "<=" => LE; case ">" => GT; case ">=" => GE
      case "==" => EQ;  case "!=" => NE
      case _    => bail()

    /** Double-domain opcode for `op`. Comparisons reuse the F* compare ops, which
     *  still write a 0/1 boolean into an (int-typed) register. */
    private def fopcodeFor(op: String): Int = op match
      case "+"  => FADD; case "-"  => FSUB; case "*" => FMUL
      case "/"  => FDIV; case "%"  => FMOD
      case "<"  => FLT;  case "<=" => FLE; case ">" => FGT; case ">=" => FGE
      case "==" => FEQ;  case "!=" => FNE
      case _    => bail()

    private def isCmp(op: String): Boolean = op match
      case "<" | "<=" | ">" | ">=" | "==" | "!=" => true
      case _                                     => false

    /** Return a register holding `r` as double bits: `r` itself if already
     *  double-typed, else a fresh register with an I2D promotion emitted. */
    private def asDouble(r: Int): Int =
      if typeOf(r) == TDouble then r
      else
        val d = freshReg(); emit(I2D, d, r, 0); setType(d, TDouble); d

    /** Emit `lr op rr` into `dst`, choosing int or double opcodes by operand
     *  type and promoting the int side on a mixed pair. Returns the result type
     *  (TInt for any comparison or all-int arithmetic; TDouble otherwise). */
    private def emitArith(op: String, dst: Int, lr: Int, rr: Int): VmType =
      if typeOf(lr) == TRef || typeOf(rr) == TRef then bail()  // no ref arithmetic/compare
      if typeOf(lr) == TDouble || typeOf(rr) == TDouble then
        emit(fopcodeFor(op), dst, asDouble(lr), asDouble(rr))
        val rt = if isCmp(op) then TInt else TDouble
        setType(dst, rt); rt
      else
        emit(opcodeFor(op), dst, lr, rr)
        setType(dst, TInt); TInt

    /** Immediate-RHS opcode for `op` (operand `c` is a constPool index). */
    private def opcodeImmFor(op: String): Int = op match
      case "+"  => ADDI; case "-"  => SUBI; case "*" => MULI
      case "/"  => DIVI; case "%"  => MODI
      case "<"  => LTI;  case "<=" => LEI; case ">" => GTI; case ">=" => GEI
      case "==" => EQI;  case "!=" => NEI
      case _    => bail()

    /** The literal `Long` value of `t` if it is an integer literal, else None. */
    private def intLiteral(t: Term): Option[Long] = t match
      case Lit.Int(v)  => Some(v.toLong)
      case Lit.Long(v) => Some(v)
      case _           => None

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
    // A bare name reuses its existing register (no copy); everything else is
    // emitted straight into a fresh register via destination-passing.
    private def compileExpr(t: Term): Int = t match
      case n: Term.Name => locals.getOrElse(n.value, bail())
      case _            => val r = freshReg(); compileInto(t, r); r

    /** Whether `callee` operates in the double domain — same syntactic scan as
     *  [[fnIsDouble]]. For a self-call this is the function's own classification. */
    private def calleeIsDouble(callee: Value.FunV): Boolean =
      if callee eq fn then fnIsDouble
      else callee.paramTypes.exists(t => doubleTypes.contains(t.trim)) ||
           callee.body.collect { case _: Lit.Double => () }.nonEmpty

    private def calleeParamType(callee: Value.FunV, i: Int): VmType =
      if i < callee.paramTypes.length then fieldVmType(callee.paramTypes(i)) else TInt

    // Compile `t`, emitting its result directly into register `dst`, and return
    // the static type written there. Destination-passing avoids the extra MOVE a
    // return-a-register scheme needs at every use site (call args, if-branches,
    // assignments), cutting the instruction count of the hot VM dispatch loop.
    private def compileInto(t: Term, dst: Int): VmType = t match
      case Lit.Int(v)    => emit(CONST, dst, constSlot(v.toLong), 0); setType(dst, TInt); TInt
      case Lit.Long(v)   => emit(CONST, dst, constSlot(v), 0); setType(dst, TInt); TInt
      case Lit.Double(v) =>
        emit(CONST, dst, constSlot(jl.Double.doubleToRawLongBits(v.toString.toDouble)), 0)
        setType(dst, TDouble); TDouble

      case n: Term.Name =>
        val r = locals.getOrElse(n.value, bail())
        if r != dst then emit(MOVE, dst, r, 0)
        val ty = typeOf(r); setType(dst, ty); ty

      case app: Term.ApplyInfix if app.argClause.values.lengthCompare(1) == 0 =>
        val rhs = app.argClause.values.head
        val op  = app.op.value
        intLiteral(rhs) match
          case Some(v) =>
            val lr = compileExpr(app.lhs)
            if typeOf(lr) == TInt then                 // fold literal RHS into an immediate op
              emit(opcodeImmFor(op), dst, lr, constSlot(v))
              setType(dst, TInt); TInt
            else                                        // double lhs: promote the int literal
              val rr = freshReg()
              emit(CONST, rr, constSlot(jl.Double.doubleToRawLongBits(v.toDouble)), 0)
              setType(rr, TDouble)
              emitArith(op, dst, lr, rr)
          case None =>
            val lr = compileExpr(app.lhs)
            val rr = compileExpr(rhs)
            emitArith(op, dst, lr, rr)

      case t: Term.If =>
        val cr  = compileExpr(t.cond)
        if typeOf(cr) != TInt then bail()        // condition must be a 0/1 boolean
        val jf  = emit(JF, cr, -1, 0)            // patch to else-start
        val tT  = compileInto(t.thenp, dst)
        val jmp = emit(JMP, -1, 0, 0)            // patch to end
        bs(jf) = ops.length                      // JF else-target: else-branch starts here
        val eT  = compileInto(t.elsep, dst)
        as(jmp) = ops.length                     // end
        if tT != eT then bail()                  // mixed-type branches → tree-walk
        setType(dst, tT); tT

      // call to self or another compilable function (int or double domain)
      case app: Term.Apply =>
        callTarget(app) match
          case Some(callee) =>
            val args = app.argClause.values
            if args.lengthCompare(callee.params.length) != 0 then bail()
            val slot    = slotFor(callee)
            val argBase = freshRegs(args.length)
            var i = 0
            while i < args.length do
              val aT   = compileInto(args(i), argBase + i)   // emit each arg straight into its slot
              val want = calleeParamType(callee, i)
              (want, aT) match
                case (TDouble, TInt) =>
                  emit(I2D, argBase + i, argBase + i, 0); setType(argBase + i, TDouble)
                case (TDouble, TDouble) | (TInt, TInt) | (TRef, TRef) => ()
                case _ => bail()                              // ref/numeric mismatch
              i += 1
            emit(CALL, dst, argBase, slot)
            val rt = if calleeIsDouble(callee) then TDouble else TInt
            setType(dst, rt); rt
          case None => bail()

      case Term.Block(stats) =>
        compileStatsInto(stats, dst)

      case Term.While(cond, body) =>
        val start = ops.length
        val cr    = compileExpr(cond)
        if typeOf(cr) != TInt then bail()        // condition must be a 0/1 boolean
        val jf    = emit(JF, cr, -1, 0)
        // Compile body as void — all stats are statements, no return value needed.
        // Avoids compileStats→compileExpr(last) which bails on Term.Assign.
        body match
          case Term.Block(ss) => ss.foreach(compileStmt)
          case _              => compileStmt(body)
        emit(JMP, start, 0, 0)
        bs(jf) = ops.length
        emit(CONST, dst, constSlot(0L), 0)       // while ⇒ unit ⇒ 0
        setType(dst, TInt); TInt

      case tm: Term.Match =>
        compileMatchInto(tm.expr, tm.casesBlock.cases, dst)

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
        if typeOf(cr) != TInt then bail()        // condition must be a 0/1 boolean
        val jf = emit(JF, cr, -1, 0)
        compileTail(ti.thenp)        // then-branch self-terminates
        bs(jf) = ops.length
        compileTail(ti.elsep)        // else-branch self-terminates

      case tm: Term.Match =>
        compileMatchTail(tm.expr, tm.casesBlock.cases)

      case app: Term.Apply if isSelfTailCall(app) =>
        val args = app.argClause.values
        if args.lengthCompare(fn.params.length) != 0 then bail()
        // Evaluate every arg into a temp BEFORE overwriting the param regs,
        // since args (e.g. `acc + n`) read the current params. Promote an int
        // arg to double when the param register is double-typed (and bail on the
        // reverse, which would silently reinterpret double bits as an int).
        val tmp = new Array[Int](args.length)
        var i = 0
        while i < args.length do
          var r = compileExpr(args(i))
          val want = typeOf(i)                       // param reg i's fixed type
          (want, typeOf(r)) match
            case (TDouble, TInt)    => r = asDouble(r)
            case (a, b) if a == b   => ()
            case _                  => bail()         // incompatible (incl. ref/numeric)
          tmp(i) = r; i += 1
        i = 0
        while i < args.length do { emit(MOVE, i, tmp(i), 0); i += 1 } // params = r0..r(n-1)
        emit(JMP, 0, 0, 0)           // loop to start

      case Term.Block(stats) =>
        if stats.isEmpty then bail()
        var rest = stats
        while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
        compileTail(rest.head.asInstanceOf[Term])

      case other =>
        val r = compileExpr(other); unifyRet(typeOf(r)); emit(RET, r, 0, 0)

    private def isSelfTailCall(app: Term.Apply): Boolean =
      fn.name.nonEmpty && (app.fun match
        case n: Term.Name => n.value == fn.name && !locals.contains(n.value)
        case _            => false)

    // A statement whose value may be discarded (loop bodies, non-final stmts).
    // `val`/`var` get a stable home register written directly by compileInto;
    // assignments write straight into the bound register — no extra MOVE.
    private def compileStmt(t: Tree): Unit = t match
      case Defn.Val(_, List(Pat.Var(nm: Term.Name)), _, rhs) =>
        val home = freshReg(); compileInto(rhs, home); locals(nm.value) = home
      case Defn.Var.After_4_7_2(_, List(Pat.Var(nm)), _, rhs) =>
        val home = freshReg(); compileInto(rhs, home); locals(nm.value) = home
      case Term.Assign(nm: Term.Name, rhs) =>
        val dst = locals.getOrElse(nm.value, bail())
        val old = typeOf(dst)
        val nt  = compileInto(rhs, dst)
        if nt != old then bail()            // a var must not change numeric domain
      case Term.Block(stats) => compileStats(stats); ()
      case e: Term            => compileExpr(e); ()
      case _                  => bail()

    private def compileStats(stats: List[Stat]): Int =
      if stats.isEmpty then bail()
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileExpr(rest.head.asInstanceOf[Term])

    private def compileStatsInto(stats: List[Stat], dst: Int): VmType =
      if stats.isEmpty then bail()
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileInto(rest.head.asInstanceOf[Term], dst)

    // ── match compilation (VM 2a) ────────────────────────────────────
    // Only the safe shape compiles: `scrut match { case Ctor(binds...) => body }`
    // over a ref scrutinee, no guards, binders are plain `Pat.Var`/`_`. Each case
    // becomes ISTAG (tag test) + JF (skip to next case) + GETFI/GETFR (bind each
    // field positionally, by declared field type). An unmatched scrutinee hits
    // MFAIL → JIT bridge falls back to the tree-walker (same MatchError). Anything
    // outside this shape bails, preserving semantics by construction.

    /** Emit ISTAG + JF + field bindings for one case. Returns the JF instruction
     *  index, to be patched to where the next case begins. */
    private def emitCaseHeader(scrutReg: Int, c: scala.meta.Case): Int =
      if c.cond.isDefined then bail()                 // guards unsupported
      c.pat match
        case Pat.Extract.After_4_6_0(fn0, argClause) =>
          val ctor = fn0 match { case n: Term.Name => n.value; case _ => bail() }
          val argPats = argClause.values
          val test = freshReg()
          emit(ISTAG, test, scrutReg, strSlot(ctor))
          val jf = emit(JF, test, -1, 0)
          val info = ctx.metaFor(ctor)
          if info == null then bail()
          val (names, types) = info
          if argPats.lengthCompare(names.length) != 0 then bail()
          var i  = 0
          var ps = argPats
          while ps.nonEmpty do
            ps.head match
              case Pat.Var(nm: Term.Name) =>
                val home = freshReg()
                val ft   = fieldVmType(types(i))
                if ft == TRef then { emit(GETFR, home, scrutReg, strSlot(names(i))); setType(home, TRef) }
                else { emit(GETFI, home, scrutReg, strSlot(names(i))); setType(home, ft) }
                locals(nm.value) = home
              case _: Pat.Wildcard => ()              // matched but unbound
              case _               => bail()
            i += 1; ps = ps.tail
          jf
        case _ => bail()

    /** Tail-position match: each arm self-terminates (RET or a self-tail loop). */
    private def compileMatchTail(scrut: Term, cases: List[scala.meta.Case]): Unit =
      val sr = compileExpr(scrut)
      if typeOf(sr) != TRef then bail()
      var rest = cases
      while rest.nonEmpty do
        val jf = emitCaseHeader(sr, rest.head)
        compileTail(rest.head.body)
        bs(jf) = ops.length                           // next case starts here
        rest = rest.tail
      emit(MFAIL, 0, 0, 0)

    /** Expression-position match: each arm writes `dst` then jumps to the end.
     *  All arms must agree on result type. */
    private def compileMatchInto(scrut: Term, cases: List[scala.meta.Case], dst: Int): VmType =
      val sr = compileExpr(scrut)
      if typeOf(sr) != TRef then bail()
      val endJumps = mutable.ArrayBuffer.empty[Int]
      var resultType: Option[VmType] = None
      var rest = cases
      while rest.nonEmpty do
        val jf = emitCaseHeader(sr, rest.head)
        val bt = compileInto(rest.head.body, dst)
        resultType match
          case None                    => resultType = Some(bt)
          case Some(prev) if prev == bt => ()
          case _                       => bail()
        endJumps += emit(JMP, -1, 0, 0)
        bs(jf) = ops.length
        rest = rest.tail
      emit(MFAIL, 0, 0, 0)
      val end = ops.length
      endJumps.foreach(j => as(j) = end)
      val rt = resultType.getOrElse(bail())
      setType(dst, rt); rt

    /** Emit the instruction stream; callee shells are resolved later by [[Ctx]]. */
    def buildInstructions(): Unit =
      val arity = fn.params.length
      if arity < 1 || arity > MaxArity then bail()
      var i = 0
      while i < arity do
        locals(fn.params(i)) = i                         // params occupy r0..r(arity-1)
        if i < fn.paramTypes.length then
          fieldVmType(fn.paramTypes(i)) match
            case TInt    => ()
            case t       => setType(i, t)                // TDouble or TRef param
        i += 1
      nextReg = arity; maxReg = arity
      compileTail(fn.body)           // emits RET / loop on every path
      // The up-front double classification must agree with the actual return
      // type derived from the RET leaves; otherwise self-call results were typed
      // wrong. Bail (→ tree-walk) rather than risk a miswrapped value.
      if fnIsDouble && !retIsDoubleOf then bail()
