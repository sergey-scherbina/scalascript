package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** JSON intrinsics for the JS (Node.js) backend (Stage 5+/E). */
val JsJsonIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("jsonStringify") -> RuntimeCall("jsonStringify"),
  QualifiedName("jsonParse")     -> RuntimeCall("jsonParse"),
  QualifiedName("jsonRead")      -> RuntimeCall("jsonRead"),
  QualifiedName("lookup")        -> RuntimeCall("lookup"),
  QualifiedName("lookupOpt")     -> RuntimeCall("lookupOpt"),
)
