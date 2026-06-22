package scalascript.codegen

/** JWT / OAuth2 / CSRF JS runtime.  Gated on the `Jwt` capability. */
val JsRuntimeJwtAuth: String = JsRuntimeResource.load("jwt-auth.mjs")
