package scalascript.interpreter.vm.jit

// Typed unboxed dispatch interfaces implemented by JIT-generated classes.
// All methods have primitive JVM descriptors so callers skip boxing.
// JitRuntime dispatches via these instead of the generic MethodHandle.invoke path.
trait LongFn1     { def apply(n: Long): Long }
trait DoubleFn1   { def apply(n: Double): Double }
trait ObjToLong   { def apply(n: AnyRef): Long }
trait ObjToDouble { def apply(n: AnyRef): Double }
trait LongFn2     { def apply(a: Long,   b: Long):   Long }
trait DoubleFn2   { def apply(a: Double, b: Double): Double }
