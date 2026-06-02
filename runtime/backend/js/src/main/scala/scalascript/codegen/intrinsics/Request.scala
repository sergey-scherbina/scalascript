package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Request validation intrinsics for the JS (Node.js) backend (Stage 5+/E). */
val JsRequestIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("requireString")      -> RuntimeCall("_restRequireString"),
  QualifiedName("optionalString")     -> RuntimeCall("optionalString"),
  QualifiedName("requireInt")         -> RuntimeCall("_restRequireInt"),
  QualifiedName("optionalInt")        -> RuntimeCall("optionalInt"),
  QualifiedName("requireDouble")      -> RuntimeCall("_restRequireDouble"),
  QualifiedName("optionalDouble")     -> RuntimeCall("optionalDouble"),
  QualifiedName("requireBool")        -> RuntimeCall("_restRequireBool"),
  QualifiedName("optionalBool")       -> RuntimeCall("optionalBool"),
  QualifiedName("requireRange")       -> RuntimeCall("requireRange"),
  QualifiedName("requireRangeDouble") -> RuntimeCall("requireRangeDouble"),
  QualifiedName("requireOneOf")       -> RuntimeCall("requireOneOf"),
)
