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
// Mixed Long+Ref dispatch interfaces — added 2026-06-03 to unblock the
// `recursiveEvalMixed` shape `def gEval(scale: Int, e: Expr): Int` where
// one arg is a primitive Long slot and the other is an InstanceV ref.
// Without these, JavacJitBackend.determineInterface returned null for
// 2-arg mixed-kind funs and Result.direct stayed null, so each call
// fell through to `MethodHandle.invoke` which auto-boxes both args.
trait LongObjToLong   { def apply(a: Long,   b: AnyRef): Long }
trait ObjLongToLong   { def apply(a: AnyRef, b: Long):   Long }
trait LongObjToDouble { def apply(a: Long,   b: AnyRef): Double }
trait ObjLongToDouble { def apply(a: AnyRef, b: Long):   Double }
