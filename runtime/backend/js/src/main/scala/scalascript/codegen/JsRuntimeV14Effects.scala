package scalascript.codegen

/** v1.4 built-in effects: Logger, Random, Clock, Env.
 *  Concatenated after `JsRuntimeAsync` wherever effects are available. */
val JsRuntimeV14Effects: String = JsRuntimeResource.load("v14effects.mjs")
