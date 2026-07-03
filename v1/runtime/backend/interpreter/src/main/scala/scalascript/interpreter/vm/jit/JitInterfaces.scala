package scalascript.interpreter.vm.jit

// Typed unboxed dispatch interfaces implemented by JIT-generated classes.
// All methods have primitive JVM descriptors so callers skip boxing.
// JitRuntime dispatches via these instead of the generic MethodHandle.invoke path.
// Zero-arg dispatch interfaces — for JIT-compiled `def workload(): Long`
// thunks (no params). Reached only via JitRuntime.tryRun0 / CallRuntime.callValue0.
trait LongFn0     { def apply(): Long }
trait DoubleFn0   { def apply(): Double }
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
// Ref-returning interface — for functions that take and return an InstanceV
// (e.g. `def getLeft(t: Tree): Tree = t match { case Node(l, _) => l }`).
// Used by LApplyR1ToRef in EvalRuntime to build f(g(item)) ref-arg chains.
trait ObjToObject     { def apply(n: AnyRef): AnyRef }
// Both-ref 2-param dispatch interfaces — for functions where both parameters
// are InstanceV/StringV/MapV refs (e.g. `def eval(env: Map, expr: Expr): Int`).
trait ObjObjToLong   { def apply(a: AnyRef, b: AnyRef): Long   }
trait ObjObjToDouble { def apply(a: AnyRef, b: AnyRef): Double  }
// Primitive-param / ref-returning interface for pure ADT builders such as
// `def build(d: Int): Expr = if d <= 0 then Num(1) else Add(build(...), ...)`.
trait LongToObject    { def apply(n: Long): AnyRef }
// While-loop runner interface — implemented by all three generated while-JIT
// class variants (WhileLong, WhileMixed, WhileMap).  The instance method
// delegates to the static `run(long[])` inside the same generated class,
// giving callers a direct virtual-dispatch path that avoids Method.invoke
// varargs-boxing and reflection overhead.
trait WhileLongRunFn  { def run(slots: Array[Long]): Unit }
// Stream-emit while-loop JIT runner.  The generated loop pushes emitted Long
// values directly into `buf` starting at `bufLen` and returns the new length.
// Caller pre-allocates buf with sufficient capacity; no bounds check inside.
trait WhileLongEmitRunFn { def run(slots: Array[Long], buf: Array[Long], bufLen: Int): Int }
// HOF dispatch marker — implemented by any JIT-generated class that represents a
// callable function value.  Stage 3.1 infrastructure; the monomorphic inline cache
// (Stage 3.4) replaces the boxed `Array[AnyRef]` call with a direct MH dispatch.
trait RefCallable { def call(args: Array[AnyRef]): AnyRef }
// Arity-3 dispatch interfaces — Stage 4.  8 Long-ref masks × 2 return kinds = 16.
// Naming: O = Object (ref param), L = long; left-to-right param order, then return type.
trait LongFn3         { def apply(a: Long,   b: Long,   c: Long):   Long   }
trait DoubleFn3       { def apply(a: Double, b: Double, c: Double): Double }
trait ObjLongLongToLong   { def apply(a: AnyRef, b: Long,   c: Long):   Long   }
trait LongObjLongToLong   { def apply(a: Long,   b: AnyRef, c: Long):   Long   }
trait LongLongObjToLong   { def apply(a: Long,   b: Long,   c: AnyRef): Long   }
trait ObjObjLongToLong    { def apply(a: AnyRef, b: AnyRef, c: Long):   Long   }
trait ObjLongObjToLong    { def apply(a: AnyRef, b: Long,   c: AnyRef): Long   }
trait LongObjObjToLong    { def apply(a: Long,   b: AnyRef, c: AnyRef): Long   }
trait ObjObjObjToLong     { def apply(a: AnyRef, b: AnyRef, c: AnyRef): Long   }
trait ObjLongLongToDouble { def apply(a: AnyRef, b: Long,   c: Long):   Double }
trait LongObjLongToDouble { def apply(a: Long,   b: AnyRef, c: Long):   Double }
trait LongLongObjToDouble { def apply(a: Long,   b: Long,   c: AnyRef): Double }
trait ObjObjLongToDouble  { def apply(a: AnyRef, b: AnyRef, c: Long):   Double }
trait ObjLongObjToDouble  { def apply(a: AnyRef, b: Long,   c: AnyRef): Double }
trait LongObjObjToDouble  { def apply(a: Long,   b: AnyRef, c: AnyRef): Double }
trait ObjObjObjToDouble   { def apply(a: AnyRef, b: AnyRef, c: AnyRef): Double }
