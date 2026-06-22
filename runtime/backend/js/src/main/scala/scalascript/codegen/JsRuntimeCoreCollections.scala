package scalascript.codegen

/** Core JS runtime (collections half) — seq / collection helpers + sync fallbacks (overridden
 *  by the Async runtime when the `Async` capability is present).  Always included. */
val JsRuntimeCoreCollections: String = JsRuntimeResource.load("core-collections.mjs")
