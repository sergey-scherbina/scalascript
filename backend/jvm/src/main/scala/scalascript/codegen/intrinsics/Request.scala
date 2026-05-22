package scalascript.codegen.jvm

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Request validation intrinsics for the JVM (Scala-CLI) backend (Stage 5+/E). */
val JvmRequestIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("requireString")      -> RuntimeCall("requireString"),
  QualifiedName("optionalString")     -> RuntimeCall("optionalString"),
  QualifiedName("requireInt")         -> RuntimeCall("requireInt"),
  QualifiedName("optionalInt")        -> RuntimeCall("optionalInt"),
  QualifiedName("requireDouble")      -> RuntimeCall("requireDouble"),
  QualifiedName("optionalDouble")     -> RuntimeCall("optionalDouble"),
  QualifiedName("requireBool")        -> RuntimeCall("requireBool"),
  QualifiedName("optionalBool")       -> RuntimeCall("optionalBool"),
  QualifiedName("requireRange")       -> RuntimeCall("requireRange"),
  QualifiedName("requireRangeDouble") -> RuntimeCall("requireRangeDouble"),
  QualifiedName("requireOneOf")       -> RuntimeCall("requireOneOf"),
)
