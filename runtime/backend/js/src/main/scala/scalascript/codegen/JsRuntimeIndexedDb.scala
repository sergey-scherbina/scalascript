package scalascript.codegen

/** Client-side IndexedDB storage JS runtime preamble.
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p2); referenced
 *  unqualified as a package-level `val`, keeping emitted JS byte-identical. */
val JsRuntimeIndexedDb: String = JsRuntimeResource.load("indexeddb.mjs")
