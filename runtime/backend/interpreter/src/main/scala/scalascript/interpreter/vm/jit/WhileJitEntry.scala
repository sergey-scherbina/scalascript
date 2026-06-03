package scalascript.interpreter.vm.jit

/** Result of `JitBackend.tryCompileWhileLong` / `tryCompileWhileMixed`.
 *
 *  For pure-long while loops (no ref-typed variables), all arrays are empty
 *  and the generated `run(long[])` method needs no TLS setup.
 *
 *  When the loop body calls:
 *  - A JIT-compiled `ObjToLong` function with a val-bound `InstanceV` arg:
 *    `refNames` holds the variable names (simple `"v"` or dotted `"v.field"`)
 *    to resolve in `interp.globals` at each invocation; `refFns` holds the
 *    compiled `ObjToLong` instances.  Generated Java reads them from
 *    `JitGlobals.getRefs()` / `JitGlobals.getRefFns()`.
 *  - A JIT-compiled `ObjToObject` function chained as an argument to an
 *    `ObjToLong` call (`leafVal(getLeft(tree))`): `refObjFns` holds the
 *    compiled `ObjToObject` instances.  Generated Java reads them from
 *    `JitGlobals.getRefObjFns()`.  The `refNames` entry for the inner arg
 *    is shared with the same `_r$i` slot.
 *  - For `tryCompileWhileMixed` (fused outer-while + foreach): `refDoubleFns`
 *    holds one `ObjToDouble` for Double-acc foreach loops.  Generated Java
 *    reads it from `JitGlobals.getRefDoubleFns()`.  The list receiver is
 *    passed via `getRefs()[0]` at call time (not stored here). */
final class WhileJitEntry(
  val method:       java.lang.reflect.Method,
  val refNames:     Array[String],
  val refFns:       Array[ObjToLong],
  val refObjFns:    Array[ObjToObject],
  val refDoubleFns: Array[ObjToDouble] = Array.empty[ObjToDouble]
)
