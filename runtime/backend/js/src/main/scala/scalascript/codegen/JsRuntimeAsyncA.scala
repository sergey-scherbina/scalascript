package scalascript.codegen

/** Async-effect JS runtime preamble (half A) — `delay`/`async`/`parallel`
 *  with interpreter/JvmGen-equivalent semantics. Split into two halves to
 *  stay under the JVM ~64 KB string-literal cap; concatenated by the
 *  `lazy val JsRuntimeAsync` combinator in `JsGen`. Extracted verbatim
 *  (jsgen-split-p4). */
val JsRuntimeAsyncA: String = JsRuntimeResource.load("asynca.mjs")
