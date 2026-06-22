package scalascript.codegen

/** JS runtime preamble (part 1a) embedded in every generated page.
 *  The preamble is split across several `JsRuntimePart*` vals because the
 *  combined source exceeds the JVM 64 KB string-literal limit; the eager
 *  `val JsRuntime` in `JsGen` concatenates them (cross-object init triggers
 *  each part on first access).  Extracted verbatim (jsgen-split-p3). */
val JsRuntimePart1a: String = JsRuntimeResource.load("part1a.mjs")
