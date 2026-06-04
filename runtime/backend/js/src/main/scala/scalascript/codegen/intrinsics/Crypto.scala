package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Crypto intrinsics for the JS (Node.js) backend. */
val JsCryptoIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("sha256")       -> RuntimeCall("_sha256"),
  QualifiedName("hmacSha256")   -> RuntimeCall("_hmacSha256"),
  QualifiedName("base64Encode") -> RuntimeCall("_base64Encode"),
  QualifiedName("base64Decode") -> RuntimeCall("_base64Decode"),
)
