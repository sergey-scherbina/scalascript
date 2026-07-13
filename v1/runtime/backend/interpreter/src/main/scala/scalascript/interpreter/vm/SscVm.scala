package scalascript.interpreter.vm

import java.lang as jl
import java.util.Objects
import scalascript.interpreter.Value

/** Proof-of-concept register-based bytecode VM for hot integer functions.
 *  See specs/vm-jit-spec.md. v0 handles `Long`-typed functions only; every
 *  register holds a `Long`, booleans are 0/1. Not wired into the production
 *  call path — exercised directly by VmCompiler + SscVmTest.
 */
object SscVm:

  // ── CALLREF IC stats (SSC_JIT_IC_STATS=1) ────────────────────────
  val icStatsEnabled: Boolean = sys.env.getOrElse("SSC_JIT_IC_STATS", "") == "1"
  private val _icHits   = new java.util.concurrent.atomic.LongAdder
  private val _icMisses = new java.util.concurrent.atomic.LongAdder
  // Stage 9 polyIC: hits broken down by which way (0..icWays-1) matched.
  private val _icHitsByWay = Array.fill(8)(new java.util.concurrent.atomic.LongAdder)
  def icHits():   Long   = _icHits.sum()
  def icMisses(): Long   = _icMisses.sum()
  def icReport(): String =
    val byWay = (0 until icWays).map(i => s"way$i=${_icHitsByWay(i).sum()}").mkString(" ")
    s"CALLREF IC: ${_icHits.sum()} hits, ${_icMisses.sum()} misses ($byWay)"

  // Stage 9 polyIC: number of FunV slots per call site. 4 covers ~all megamorphic
  // sites observed (List[Shape] with 2-3 ADT cases plus an outlier). One slot per
  // way holds the FunV identity; the next holds its compiled CompiledFn (or null
  // if VmCompiler.compile failed for it).
  val icWays: Int    = 4
  val icStride: Int  = icWays * 2

  // ── opcodes ──────────────────────────────────────────────────────
  final val CONST = 0
  final val MOVE  = 1
  final val ADD   = 2
  final val SUB   = 3
  final val MUL   = 4
  final val DIV   = 5
  final val MOD   = 6
  final val LT    = 7
  final val LE    = 8
  final val GT    = 9
  final val GE    = 10
  final val EQ    = 11
  final val NE    = 12
  final val JMP   = 13
  final val JF    = 14
  final val CALL  = 15
  final val RET   = 16
  // Immediate-RHS variants: operand `c` is a constPool index, not a register.
  // Emitted when an infix op has a literal right-hand side, folding the CONST
  // that would otherwise load the literal into a register first.
  final val ADDI  = 17
  final val SUBI  = 18
  final val MULI  = 19
  final val DIVI  = 20
  final val MODI  = 21
  final val LTI   = 22
  final val LEI   = 23
  final val GTI   = 24
  final val GEI   = 25
  final val EQI   = 26
  final val NEI   = 27
  // Double-typed arithmetic & ordering. Registers still hold a `Long`, but for
  // these opcodes the bits are interpreted as an IEEE-754 double via
  // `longBitsToDouble`. Comparisons write a 0/1 `Long` (a boolean), exactly like
  // their integer counterparts, so JF/branching is type-agnostic.
  final val FADD  = 28
  final val FSUB  = 29
  final val FMUL  = 30
  final val FDIV  = 31
  final val FMOD  = 32
  final val FLT   = 33
  final val FLE   = 34
  final val FGT   = 35
  final val FGE   = 36
  final val FEQ   = 37
  final val FNE   = 38
  // Promote an int-holding register to double bits: dst = bits(double(b)).
  final val I2D   = 39
  // ── ref-value opcodes (VM 2a): operate on the parallel ref bank ──────
  // Registers are dual-bank: every index `r` addresses both a `Long` cell
  // (`stack`) and an `AnyRef` cell (`refStack`). Numeric opcodes use `stack`;
  // these use `refStack`. MOVE and CALL copy BOTH banks so a ref flows through
  // a register move transparently.
  //
  // ISTAG dst, b, c: dst(int) = 1 if refStack(b) is an InstanceV whose typeName
  //   == strPool(c), else 0. Lets a `match` dispatch on the constructor tag.
  final val ISTAG = 40
  // GETFI dst, b, c: dst(num) = numeric field strPool(c) of the InstanceV in
  //   refStack(b). IntV → its Long; DoubleV → its double-bits (the compiler sets
  //   dst's static type from the declared field type, so later opcodes agree).
  final val GETFI = 41
  // GETFR dst, b, c: refStack(dst) = ref field strPool(c) of refStack(b).
  final val GETFR = 42
  // MFAIL: no case matched a compiled `match`. Throws so the JIT bridge falls
  //   back to the tree-walker, which recomputes (pure subset) and raises the
  //   same MatchError. Never reached for an exhaustive sealed match.
  final val MFAIL = 43
  // ── string / ref-equality opcodes (VM 2b) ────────────────────────────────
  // LOADS dst, b, 0: refStack(dst) = Value.StringV(strPool(b)).
  //   Loads a string literal from strPool into the ref bank. Type: TRef.
  final val LOADS = 44
  // EQREF dst, a, b: stack(dst) = Objects.equals(refStack(a), refStack(b)) ? 1 : 0
  //   Structural equality on ref-bank values (String, InstanceV, null).
  final val EQREF = 45
  // NEREF dst, a, b: inverse of EQREF.
  final val NEREF = 46
  // CALLREF dst, refSlot, argBase: invoke the FunV stored in refStack(refSlot) with
  //   nargs args taken from the long+ref banks at argBase..argBase+nargs-1 (nargs
  //   is derived at runtime from FunV.params.length). Result → stack(dst) (Long) or
  //   refStack(dst) (AnyRef). Slow path: invokes via JitGlobals.getInterp().invoke —
  //   the monomorphic IC (Stage 3.4) replaces this with a cached CompiledFn dispatch.
  final val CALLREF = 47
  // LOADFV dst, poolSlot, 0: load a compile-time FunV constant from funVPool(poolSlot)
  //   into refStack(dst). Used to represent non-capturing lambda literals passed as
  //   HOF arguments — the FunV is created at VmCompiler time and stored in funVPool.
  final val LOADFV = 48
  // LOADFVCAP dst, poolSlot, capBase: like LOADFV but materialises a CAPTURING lambda at runtime — a
  //   new FunV from the pooled template funVPool(poolSlot) with a `closure` Map snapshotted from the
  //   capture registers at capBase (names + kinds in funVCapturePool(poolSlot); kind 0=Int, 1=Double,
  //   2=Ref). The captured body has free names so it never JIT-compiles → CALLREF always dispatches it
  //   via interp.invoke, which reads the closure — matching the interpreter's own value-snapshot of
  //   frame-local captures (EvalRuntime Term.Function).
  final val LOADFVCAP = 54
  // RETREF r, 0, 0: return refStack(r) from the function (ref-typed return).
  //   Stores the ref in the TLS slot `lastRefResult` and returns 0L from `exec` so
  //   the caller (JitRuntime.runVm) can read it. Used when the function's declared
  //   return type is String, an ADT, or any other ref-typed value.
  final val RETREF = 49

  // SSTR dst, src, methodSlot: refStack(dst) = StringV(m(refStack(src).v)) where
  //   m is the no-arg String→String method named strPool(methodSlot)
  //   (trim / toLowerCase / toUpperCase). The compiler only emits this for a
  //   ref register already typed "String", so the cast is safe.
  final val SSTR = 50

  // ── List-iteration opcodes (Slice A: inline-lambda foldLeft) ──────────────
  // A foldLeft cursor is the bare underlying `List[Value]` of a ListV, held in
  // the ref bank (tail is O(1), no per-iteration re-wrap). Only emitted when the
  // receiver's declared element type is statically Int (VmCompiler gate), so the
  // IntV unbox in LITERNXI can never see a non-IntV element.
  //
  // LITERINIT dst, b, 0: refStack(dst) = (refStack(b): Value.ListV).items.
  final val LITERINIT = 51
  // LITERHN dst, b, 0: stack(dst) = (refStack(b): List[Value]).nonEmpty ? 1 : 0.
  final val LITERHN = 52
  // LITERNXI dst, b, 0: l = refStack(b): List[Value]; stack(dst) = l.head.(IntV).v;
  //   refStack(b) = l.tail (advance the cursor in place).
  final val LITERNXI = 53

  /** Per-capturing-lambda metadata for LOADFVCAP: the captured variable names and their VM kinds
    * (0=Int/Long/Boolean, 1=Double, 2=Ref), parallel to [[CompiledFn.funVPool]] by pool slot. */
  final class FunVCapture(val names: Array[String], val kinds: Array[Byte])

  /** A compiled function: parallel instruction arrays + pools.
   *  `op(i)` is the opcode; `a/b/c(i)` its operands (meaning per §4 of spec).
   *  `constPool` backs CONST; `callPool` backs CALL (callee by slot). */
  final class CompiledFn(
    val name:      String,
    val arity:     Int,
    val numRegs:   Int,
    val op:        Array[Int],
    val a:         Array[Int],
    val b:         Array[Int],
    val c:         Array[Int],
    val constPool: Array[Long],
    val callPool:  Array[CompiledFn],
    // True when the function's result register holds double bits (see I2D /
    // F* opcodes). Lets the JIT bridge wrap the raw `Long` in DoubleV vs IntV.
    val retIsDouble: Boolean = false,
    // Per-parameter domain: paramIsDouble(i) is true when register i is read by
    // double opcodes. The JIT bridge uses this to marshal each incoming Value
    // into the correct raw `Long` (int) or double-bits representation.
    val paramIsDouble: Array[Boolean] = Array.empty,
    // Per-parameter ref flag: paramIsRef(i) is true when param i holds a ref
    // value (an InstanceV) marshalled into the ref bank rather than a numeric.
    val paramIsRef: Array[Boolean] = Array.empty,
    // String pool backing ISTAG (constructor tags) and GETFI/GETFR (field names).
    val strPool: Array[String] = Array.empty,
    // True when the function returns Boolean: the raw 0/1 Long result must be
    // wrapped in BoolV (not IntV) by the JIT bridge.
    val retIsBool: Boolean = false,
    // True when the function uses RETREF (ref-typed return: String, InstanceV, etc.).
    // The JIT bridge reads the actual AnyRef return from SscVm.lastRefResult after exec.
    val retIsRef: Boolean = false,
    // Pool of non-capturing FunV constants for LOADFV opcode (Stage 3.3).
    val funVPool: Array[Value.FunV] = Array.empty,
    // Parallel to funVPool: LOADFVCAP capture metadata per pool slot (null for a non-capturing
    // LOADFV slot). Empty when the function materialises no capturing lambdas.
    val funVCapturePool: Array[FunVCapture] = Array.empty,
    // Polymorphic inline cache for CALLREF (Stage 9, was monomorphic in 3.4).
    // Sized to `op.length * icStride`; for each pc we keep `icWays` (FunV,
    // CompiledFn) pairs. A non-null FunV slot with a null CompiledFn means
    // "seen but not compilable" — never retried via VmCompiler at that way.
    val callRefCache: Array[AnyRef] = Array.empty,
    // Stage 9 polyIC: per-call-site round-robin victim index (0..icWays-1).
    // On miss with all ways occupied, the entry at icHead(pc) is overwritten
    // and the head advances. One byte per pc keeps memory overhead low.
    val icHead: Array[Byte] = Array.empty
  )

  // Thread-local slot for RETREF: exec stores the AnyRef return here so the
  // caller (JitRuntime.runVm) can read it without changing exec's return type.
  private val tlRefReturn = new ThreadLocal[Array[AnyRef]]:
    override def initialValue(): Array[AnyRef] = new Array[AnyRef](1)

  /** After exec returns from a RETREF function, returns the stored ref result. */
  def lastRefResult: AnyRef = tlRefReturn.get()(0)

  /** Execute `fn` with a frame window based at `base` in shared `stack`.
   *  Arguments must already sit at stack(base .. base+arity-1).
   *  Returns the function's `Long` result. Recurses on CALL using a window
   *  bumped past the current frame — no per-call heap allocation. */
  def exec(fn: CompiledFn, stack: Array[Long], refStack: Array[AnyRef], base: Int): Long =
    val op = fn.op; val a = fn.a; val b = fn.b; val c = fn.c
    val k  = fn.constPool
    val sp = fn.strPool
    var pc = 0
    while true do
      (op(pc): @annotation.switch) match
        case CONST => stack(base + a(pc)) = k(b(pc))
        case MOVE  =>
          stack(base + a(pc))    = stack(base + b(pc))
          refStack(base + a(pc)) = refStack(base + b(pc))
        case ADD   => stack(base + a(pc)) = stack(base + b(pc)) + stack(base + c(pc))
        case SUB   => stack(base + a(pc)) = stack(base + b(pc)) - stack(base + c(pc))
        case MUL   => stack(base + a(pc)) = stack(base + b(pc)) * stack(base + c(pc))
        case DIV   => stack(base + a(pc)) = stack(base + b(pc)) / stack(base + c(pc))
        case MOD   => stack(base + a(pc)) = stack(base + b(pc)) % stack(base + c(pc))
        case LT    => stack(base + a(pc)) = if stack(base + b(pc)) <  stack(base + c(pc)) then 1L else 0L
        case LE    => stack(base + a(pc)) = if stack(base + b(pc)) <= stack(base + c(pc)) then 1L else 0L
        case GT    => stack(base + a(pc)) = if stack(base + b(pc)) >  stack(base + c(pc)) then 1L else 0L
        case GE    => stack(base + a(pc)) = if stack(base + b(pc)) >= stack(base + c(pc)) then 1L else 0L
        case EQ    => stack(base + a(pc)) = if stack(base + b(pc)) == stack(base + c(pc)) then 1L else 0L
        case NE    => stack(base + a(pc)) = if stack(base + b(pc)) != stack(base + c(pc)) then 1L else 0L
        case ADDI  => stack(base + a(pc)) = stack(base + b(pc)) + k(c(pc))
        case SUBI  => stack(base + a(pc)) = stack(base + b(pc)) - k(c(pc))
        case MULI  => stack(base + a(pc)) = stack(base + b(pc)) * k(c(pc))
        case DIVI  => stack(base + a(pc)) = stack(base + b(pc)) / k(c(pc))
        case MODI  => stack(base + a(pc)) = stack(base + b(pc)) % k(c(pc))
        case LTI   => stack(base + a(pc)) = if stack(base + b(pc)) <  k(c(pc)) then 1L else 0L
        case LEI   => stack(base + a(pc)) = if stack(base + b(pc)) <= k(c(pc)) then 1L else 0L
        case GTI   => stack(base + a(pc)) = if stack(base + b(pc)) >  k(c(pc)) then 1L else 0L
        case GEI   => stack(base + a(pc)) = if stack(base + b(pc)) >= k(c(pc)) then 1L else 0L
        case EQI   => stack(base + a(pc)) = if stack(base + b(pc)) == k(c(pc)) then 1L else 0L
        case NEI   => stack(base + a(pc)) = if stack(base + b(pc)) != k(c(pc)) then 1L else 0L
        case FADD  => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(jl.Double.longBitsToDouble(stack(base + b(pc))) + jl.Double.longBitsToDouble(stack(base + c(pc))))
        case FSUB  => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(jl.Double.longBitsToDouble(stack(base + b(pc))) - jl.Double.longBitsToDouble(stack(base + c(pc))))
        case FMUL  => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(jl.Double.longBitsToDouble(stack(base + b(pc))) * jl.Double.longBitsToDouble(stack(base + c(pc))))
        case FDIV  => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(jl.Double.longBitsToDouble(stack(base + b(pc))) / jl.Double.longBitsToDouble(stack(base + c(pc))))
        case FMOD  => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(jl.Double.longBitsToDouble(stack(base + b(pc))) % jl.Double.longBitsToDouble(stack(base + c(pc))))
        case FLT   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) <  jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case FLE   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) <= jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case FGT   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) >  jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case FGE   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) >= jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case FEQ   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) == jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case FNE   => stack(base + a(pc)) = if jl.Double.longBitsToDouble(stack(base + b(pc))) != jl.Double.longBitsToDouble(stack(base + c(pc))) then 1L else 0L
        case I2D   => stack(base + a(pc)) = jl.Double.doubleToRawLongBits(stack(base + b(pc)).toDouble)
        case ISTAG =>
          stack(base + a(pc)) = refStack(base + b(pc)) match
            case inst: Value.InstanceV if inst.typeName == sp(c(pc)) => 1L
            case _                                                   => 0L
        case GETFI =>
          refStack(base + b(pc)) match
            case str: Value.StringV =>
              stack(base + a(pc)) = sp(c(pc)) match
                case "length"      => str.v.length.toLong
                case "isEmpty"     => if str.v.isEmpty  then 1L else 0L
                case "nonEmpty"    => if str.v.nonEmpty then 1L else 0L
                // `s.toInt`/`s.toLong` mirror the interpreter, which parses via
                // `s.toLong` and lets a malformed string throw NumberFormatException
                // (DispatchRuntime `case "toInt" => pureIntV(s.toLong)`).
                case "toInt" | "toLong" => str.v.toLong
                case f          => throw new RuntimeException(s"GETFI: unknown String field '$f'")
            case ref =>
              val inst = ref.asInstanceOf[Value.InstanceV]
              val arr  = inst.fieldsArr
              val fv: Value =
                if arr != null then
                  val names = inst.fieldNames
                  val fname = sp(c(pc))
                  var idx = 0
                  while idx < names.length && names(idx) != fname do idx += 1
                  arr(idx).asInstanceOf[Value]
                else
                  inst.fields(sp(c(pc)))
              stack(base + a(pc)) = fv match
                case Value.IntV(x)    => x
                case Value.DoubleV(d) => jl.Double.doubleToRawLongBits(d)
                case other            => throw new RuntimeException(s"GETFI: non-numeric field ${other}")
        case GETFR =>
          val inst = refStack(base + b(pc)).asInstanceOf[Value.InstanceV]
          val arr  = inst.fieldsArr
          if arr != null then
            val names = inst.fieldNames
            val fname = sp(c(pc))
            var idx = 0
            while idx < names.length && names(idx) != fname do idx += 1
            refStack(base + a(pc)) = arr(idx).asInstanceOf[AnyRef]
          else
            refStack(base + a(pc)) = inst.fields(sp(c(pc))).asInstanceOf[AnyRef]
        case MFAIL  => throw new RuntimeException("VM match: no case matched")
        case SSTR =>
          val sv = refStack(base + b(pc)).asInstanceOf[Value.StringV]
          refStack(base + a(pc)) = sp(c(pc)) match
            case "trim"        => Value.StringV(sv.v.trim)
            case "toLowerCase" => Value.StringV(sv.v.toLowerCase)
            case "toUpperCase" => Value.StringV(sv.v.toUpperCase)
            case m             => throw new RuntimeException(s"SSTR: unknown String method '$m'")
        case LITERINIT =>
          refStack(base + a(pc)) = refStack(base + b(pc)).asInstanceOf[Value.ListV].items
        case LITERHN =>
          stack(base + a(pc)) =
            if refStack(base + b(pc)).asInstanceOf[List[Value]].nonEmpty then 1L else 0L
        case LITERNXI =>
          val l = refStack(base + b(pc)).asInstanceOf[List[Value]]
          stack(base + a(pc)) = l.head.asInstanceOf[Value.IntV].v
          refStack(base + b(pc)) = l.tail
        case LOADS  => refStack(base + a(pc)) = Value.StringV(sp(b(pc)))
        case EQREF  => stack(base + a(pc)) = if Objects.equals(refStack(base + b(pc)), refStack(base + c(pc))) then 1L else 0L
        case NEREF  => stack(base + a(pc)) = if !Objects.equals(refStack(base + b(pc)), refStack(base + c(pc))) then 1L else 0L
        case JMP   => pc = a(pc); pc -= 1  // -1 cancels the trailing pc += 1
        case JF    =>
          if stack(base + a(pc)) == 0L then { pc = b(pc); pc -= 1 }
        case CALL  =>
          val callee  = fn.callPool(c(pc))
          val argBase = base + b(pc)
          val newBase = base + fn.numRegs
          // copy the contiguous arg window into the callee's r0..r(arity-1).
          // Copy BOTH banks: a register is numeric xor ref, but copying the
          // unused cell is harmless and keeps CALL type-agnostic.
          var i = 0
          while i < callee.arity do
            stack(newBase + i)    = stack(argBase + i)
            refStack(newBase + i) = refStack(argBase + i)
            i += 1
          ensureCapacity(stack, newBase + callee.numRegs)
          stack(base + a(pc)) = exec(callee, stack, refStack, newBase)
          // busi seq-74 — a ref-returning callee stashes its result in tlRefReturn
          // and returns 0L; without copying it into the caller's ref bank the ref
          // is lost and a TRef destination reads null. (Mirror of RETREF/CALLREF.)
          if callee.retIsRef then refStack(base + a(pc)) = tlRefReturn.get()(0)
        case LOADFV  => refStack(base + a(pc)) = fn.funVPool(b(pc))
        case LOADFVCAP =>
          val tmpl    = fn.funVPool(b(pc))
          val cap     = fn.funVCapturePool(b(pc))
          val capBase = base + c(pc)
          var m = Map.empty[String, Value]
          var i = 0
          while i < cap.names.length do
            val r = capBase + i
            val v: Value = (cap.kinds(i): @scala.annotation.switch) match
              case 2 => refStack(r).asInstanceOf[Value]                        // ref capture
              case 1 => Value.doubleV(jl.Double.longBitsToDouble(stack(r)))     // double
              case _ => Value.intV(stack(r))                                    // int / long / boolean
            m = m.updated(cap.names(i), v)
            i += 1
          refStack(base + a(pc)) = tmpl.copy(closure = m)
        case CALLREF =>
          val funV    = refStack(base + b(pc)).asInstanceOf[Value.FunV]
          val argBase = base + c(pc)
          val cache   = fn.callRefCache
          val baseIdx = pc * icStride
          // Stage 9 polyIC: linear scan over icWays for a matching FunV. The
          // typical site is mono- or bi-morphic, so the loop bails on the first
          // hit; the cost on miss is at most `icWays` ref compares.
          var hitWay  = -1
          var cachedCf: CompiledFn = null
          if cache.length >= baseIdx + icStride then
            var w = 0
            while w < icWays && hitWay < 0 do
              val slot = baseIdx + w * 2
              if (cache(slot) eq funV) && cache(slot + 1) != null then
                hitWay  = w
                cachedCf = cache(slot + 1).asInstanceOf[CompiledFn]
              w += 1
          if hitWay >= 0 then
            if icStatsEnabled then
              _icHits.increment()
              _icHitsByWay(hitWay).increment()
            val newBase  = base + fn.numRegs
            var i = 0
            while i < cachedCf.arity do
              stack(newBase + i)    = stack(argBase + i)
              refStack(newBase + i) = refStack(argBase + i)
              i += 1
            ensureCapacity(stack, newBase + cachedCf.numRegs)
            stack(base + a(pc)) = exec(cachedCf, stack, refStack, newBase)
            if cachedCf.retIsRef then refStack(base + a(pc)) = tlRefReturn.get()(0)
          else
            // Slow path: dispatch through interp.invoke.
            if icStatsEnabled then _icMisses.increment()
            val nargs   = funV.params.length
            val interp  = jit.JitGlobals.getInterp()
            if interp == null then throw new RuntimeException("CALLREF: no interpreter in TLS")
            var argIdx  = 0
            val argList = new scala.collection.mutable.ListBuffer[Value]
            while argIdx < nargs do
              val r     = argBase + argIdx
              val pType = if argIdx < funV.paramTypes.length then funV.paramTypes(argIdx).trim else "Int"
              val v =
                if refStack(r) != null && refStack(r).isInstanceOf[Value] then
                  refStack(r).asInstanceOf[Value]
                else if pType == "Double" || pType == "Float" then
                  Value.doubleV(jl.Double.longBitsToDouble(stack(r)))
                else
                  Value.intV(stack(r))
              argList += v
              argIdx += 1
            val result = interp.invoke(funV, argList.toList)
            result match
              case Value.IntV(x)    => stack(base + a(pc))    = x
              case Value.BoolV(v)   => stack(base + a(pc))    = if v then 1L else 0L
              case Value.DoubleV(d) => stack(base + a(pc))    = jl.Double.doubleToRawLongBits(d)
              case other: Value     => refStack(base + a(pc)) = other
            // Stage 9 polyIC populate path: prefer an empty way; if all `icWays`
            // slots are filled with other FunVs, round-robin replace via icHead.
            // "seen but not compilable" is recorded as (funV, null) and only
            // re-tried on round-robin eviction — not on every subsequent miss.
            if cache.length >= baseIdx + icStride then
              var sameFunV = -1
              var emptyWay = -1
              var w        = 0
              while w < icWays && sameFunV < 0 do
                val slot = baseIdx + w * 2
                if cache(slot) eq funV then sameFunV = w
                else if emptyWay < 0 && cache(slot) == null then emptyWay = w
                w += 1
              if sameFunV < 0 then
                val victim =
                  if emptyWay >= 0 then emptyWay
                  else
                    // All ways occupied: evict the round-robin head.
                    val v = (fn.icHead(pc) & 0xff) % icWays
                    fn.icHead(pc) = ((v + 1) % icWays).toByte
                    v
                val slot = baseIdx + victim * 2
                cache(slot)     = funV
                cache(slot + 1) = null
                VmCompiler.compile(funV) match
                  case Some(cf) => cache(slot + 1) = cf
                  case None     => ()
        case RET    => return stack(base + a(pc))
        case RETREF => tlRefReturn.get()(0) = refStack(base + a(pc)); return 0L
      pc += 1
    0L // unreachable

  /** Thrown when the frame stack is too small. Callers (JitRuntime) catch
   *  this, grow the backing array, and retry from the top. A distinct type
   *  so genuine VM errors are never mistaken for a recoverable overflow. */
  final class FrameOverflow(val need: Int) extends RuntimeException

  // Frames live in a per-run Array[Long]; deep recursion can exceed the
  // initial size. We bounds-check defensively and signal FrameOverflow so
  // the caller can grow the array rather than silently corrupting memory.
  private def ensureCapacity(stack: Array[Long], need: Int): Unit =
    if need > stack.length then throw new FrameOverflow(need)

  /** Allocate a frame stack large enough for `depth` frames of `fn`,
   *  place `args` at the base, and run. Convenience entry for tests/bench. */
  def run(fn: CompiledFn, args: Array[Long], maxDepth: Int = 4096): Long =
    val size  = fn.numRegs.max(1) * (maxDepth + 1) + 16
    val stack = new Array[Long](size)
    val refs  = new Array[AnyRef](size)
    var i = 0
    while i < args.length do { stack(i) = args(i); i += 1 }
    exec(fn, stack, refs, 0)

  /** Ref-aware convenience entry: numeric args in `args`, ref args (by register
   *  index) in `refArgs`. Used by tests/bench for functions taking ADT params. */
  def runRef(fn: CompiledFn, args: Array[Long], refArgs: Array[AnyRef], maxDepth: Int = 4096): Long =
    val size  = fn.numRegs.max(1) * (maxDepth + 1) + 16
    val stack = new Array[Long](size)
    val refs  = new Array[AnyRef](size)
    var i = 0
    while i < args.length do { stack(i) = args(i); i += 1 }
    i = 0
    while i < refArgs.length do { refs(i) = refArgs(i); i += 1 }
    exec(fn, stack, refs, 0)
