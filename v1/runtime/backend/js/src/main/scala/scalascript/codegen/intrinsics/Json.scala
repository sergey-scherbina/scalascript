package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** JSON intrinsics for the JS (Node.js) backend (Stage 5+/E). */
val JsJsonIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("jsonStringify") -> RuntimeCall("_ssc_ui_jsonStringify"),
  QualifiedName("jsonParse")     -> RuntimeCall("jsonParse"),
  QualifiedName("jsonRead")      -> RuntimeCall("jsonRead"),
  QualifiedName("jsonValue")     -> RuntimeCall("_ssc_ui_jsonValue"),
  QualifiedName("lookup")        -> RuntimeCall("lookup"),
  QualifiedName("lookupOpt")     -> RuntimeCall("lookupOpt"),
)
