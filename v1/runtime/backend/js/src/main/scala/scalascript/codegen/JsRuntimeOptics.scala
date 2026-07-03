package scalascript.codegen

/** Optics JS runtime preamble — Lens / Optional / Traversal / Prism.
 *
 *  The JavaScript now lives in `resources/scalascript/js-runtime/optics.mjs` (a real, lintable
 *  `.mjs` file); this `val` loads it verbatim via [[JsRuntimeResource]] so the emitted JS stays
 *  byte-identical and every call site is unchanged. See `specs/js-runtime-resources.md`. */
val JsRuntimeOptics: String = JsRuntimeResource.load("optics.mjs")
