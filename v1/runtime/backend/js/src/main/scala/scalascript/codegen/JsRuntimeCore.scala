package scalascript.codegen

/** Core JS runtime preamble — the always-included base (output / `Console` / `_println`,
 *  plus core helpers), loaded from `core.mjs`.  The runtime is split into named fragments
 *  (`JsRuntimeHttpServer` / `JsRuntimeJwtAuth` / `JsRuntimeWsServer` / `JsRuntimeCoreDispatch`
 *  / `JsRuntimeCoreCollections` / capability fragments) that `JsGen.generateRuntime`
 *  concatenates on demand so unused sections are omitted; see `specs/js-runtime-resources.md`. */
val JsRuntimeCore: String = JsRuntimeResource.load("core.mjs")
