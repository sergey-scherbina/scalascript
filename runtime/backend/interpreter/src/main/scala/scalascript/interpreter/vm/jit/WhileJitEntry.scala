package scalascript.interpreter.vm.jit

/** Result of `JitBackend.tryCompileWhileLong`.
 *
 *  For pure-long while loops (no ref-typed variables), `refNames` and
 *  `refFns` are empty — the generated `run(long[])` method needs no
 *  TLS setup.
 *
 *  When the loop body calls a JIT-compiled `ObjToLong` function with a
 *  val-bound `InstanceV` argument, the generated code reads ref values
 *  from `JitGlobals.getRefs()` and function instances from
 *  `JitGlobals.getRefFns()`.  `refNames` holds the variable names to
 *  look up in `interp.globals` at each invocation; `refFns` holds the
 *  corresponding compiled `ObjToLong` instances (parallel to the `_fnN`
 *  locals in the generated Java). */
final class WhileJitEntry(
  val method:   java.lang.reflect.Method,
  val refNames: Array[String],
  val refFns:   Array[ObjToLong]
)
