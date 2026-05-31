package scalascript.interpreter.vm

/** Proof-of-concept register-based bytecode VM for hot integer functions.
 *  See docs/vm-jit-spec.md. v0 handles `Long`-typed functions only; every
 *  register holds a `Long`, booleans are 0/1. Not wired into the production
 *  call path — exercised directly by VmCompiler + VmJitBench.
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
    val callPool:  Array[CompiledFn]
  )

  /** Execute `fn` with a frame window based at `base` in shared `stack`.
   *  Arguments must already sit at stack(base .. base+arity-1).
   *  Returns the function's `Long` result. Recurses on CALL using a window
   *  bumped past the current frame — no per-call heap allocation. */
  def exec(fn: CompiledFn, stack: Array[Long], base: Int): Long =
    val op = fn.op; val a = fn.a; val b = fn.b; val c = fn.c
    val k  = fn.constPool
    var pc = 0
    while true do
      (op(pc): @annotation.switch) match
        case CONST => stack(base + a(pc)) = k(b(pc))
        case MOVE  => stack(base + a(pc)) = stack(base + b(pc))
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
        case JMP   => pc = a(pc); pc -= 1  // -1 cancels the trailing pc += 1
        case JF    =>
          if stack(base + a(pc)) == 0L then { pc = b(pc); pc -= 1 }
        case CALL  =>
          val callee  = fn.callPool(c(pc))
          val argBase = base + b(pc)
          val newBase = base + fn.numRegs
          // copy the contiguous arg window into the callee's r0..r(arity-1)
          var i = 0
          while i < callee.arity do
            stack(newBase + i) = stack(argBase + i)
            i += 1
          ensureCapacity(stack, newBase + callee.numRegs)
          stack(base + a(pc)) = exec(callee, stack, newBase)
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
    val stack = new Array[Long](fn.numRegs.max(1) * (maxDepth + 1) + 16)
    var i = 0
    while i < args.length do { stack(i) = args(i); i += 1 }
    exec(fn, stack, 0)
