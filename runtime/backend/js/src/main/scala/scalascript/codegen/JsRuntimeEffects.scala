package scalascript.codegen

/** v1.4 built-in effects: Logger, Random, Clock, Env.
 *  Concatenated after `JsRuntimeAsync` wherever effects are available. */
val JsRuntimeEffects: String = JsRuntimeResource.load("effects.mjs")
