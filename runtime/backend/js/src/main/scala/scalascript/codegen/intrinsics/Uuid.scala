package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** UUID intrinsics for the JS (Node.js) backend. */
val JsUuidIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("uuidV4")               -> RuntimeCall("uuidV4"),
  QualifiedName("uuidV7")               -> RuntimeCall("uuidV7"),
  QualifiedName("rawUuidV4")            -> RuntimeCall("uuidV4"),
  QualifiedName("rawUuidV7")            -> RuntimeCall("uuidV7"),
  QualifiedName("uuidFromString")       -> RuntimeCall("uuidFromString"),
  QualifiedName("uuidUnsafeFromString") -> RuntimeCall("uuidUnsafeFromString"),
  QualifiedName("uuidIsValid")          -> RuntimeCall("uuidIsValid"),
)
