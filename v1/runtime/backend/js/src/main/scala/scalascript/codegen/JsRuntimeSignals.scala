package scalascript.codegen

/** Reactive-signals JS runtime preamble (fine-grained reactivity).
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p2); referenced
 *  unqualified as a package-level `val`, keeping emitted JS byte-identical. */
val JsRuntimeSignals: String = JsRuntimeResource.load("signals.mjs")
