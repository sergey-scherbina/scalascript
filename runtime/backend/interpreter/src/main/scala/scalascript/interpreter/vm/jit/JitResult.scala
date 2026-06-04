package scalascript.interpreter.vm.jit

import java.lang.invoke.MethodHandle

/** Compilation result from a JIT backend.
 *
 *  `paramIsRef(i)` is true when the i-th param is passed as an `Object` (an
 *  `InstanceV`); otherwise the param is `long` (Int case) when
 *  `resultIsDouble=false`, or `double` (all-double case) when
 *  `resultIsDouble=true`. `resultIsDouble` drives the caller's
 *  IntV-vs-DoubleV result wrapping in `JitRuntime`; `resultIsRef`
 *  marks methods returning a `Value` object directly.
 *
 *  `direct`, when non-null, is an instance of one of the `JitInterfaces`
 *  traits (LongFn1 / DoubleFn1 / ObjToLong / ObjToDouble / LongFn2 /
 *  DoubleFn2) whose `apply` method calls the JIT-generated static method
 *  directly with unboxed primitives, eliminating the Long.valueOf allocations
 *  that `MethodHandle.invoke` produces. `JitRuntime.invokeBytecode*` uses
 *  this when non-null; falls back to `mh` when null (mixed-param functions
 *  or functions where instantiation failed). */
final class JitResult(
  val mh:             MethodHandle,
  val paramIsRef:     Array[Boolean],
  val resultIsDouble: Boolean = false,
  val direct:         AnyRef | Null = null,
  val resultIsRef:    Boolean = false
)
