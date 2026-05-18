package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** HTTP server intrinsics for the JS (Node.js) backend.
 *
 *  route, serve, and stop are JavaScript `function` declarations in
 *  `JsRuntimePart1b` that JsGen always prepends.  `RuntimeCall` entries
 *  here satisfy `CapabilityCheck` so any program calling these `extern def`s
 *  is accepted by the JS backend. */
val JsHttpIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("route") -> RuntimeCall("route"),
  QualifiedName("serve") -> RuntimeCall("serve"),
  QualifiedName("stop")  -> RuntimeCall("stop")
)
