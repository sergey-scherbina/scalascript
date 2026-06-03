package scalascript.interpreter.vm

import java.lang as jl
import scalascript.interpreter.Value

/** Proof-of-concept register-based bytecode VM for hot integer functions.
 *  See docs/vm-jit-spec.md. v0 handles `Long`-typed functions only; every
 *  register holds a `Long`, booleans are 0/1. Not wired into the production
 *  call path — exercised directly by VmCompiler + SscVmTest.
 */
object SscVm:

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
    val strPool: Array[String] = Array.empty
  )

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
          val inst = refStack(base + b(pc)).asInstanceOf[Value.InstanceV]
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
        case MFAIL => throw new RuntimeException("VM match: no case matched")
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
        case RET   => return stack(base + a(pc))
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
