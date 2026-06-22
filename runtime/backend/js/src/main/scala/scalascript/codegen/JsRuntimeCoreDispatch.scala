package scalascript.codegen

/** Core JS runtime (dispatch half) — the `_Char` box, method `_dispatch`, `_show`,
 *  `_tupleConcat`, the Free-monad effect machinery, and `fs`.  Always included. */
val JsRuntimeCoreDispatch: String = JsRuntimeResource.load("core-dispatch.mjs")
