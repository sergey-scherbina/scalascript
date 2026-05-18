package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Core language intrinsics for the JS (Node.js) backend (Stage 5+/E). */
val JsCoreIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("assert")     -> RuntimeCall("assert"),
  QualifiedName("require")    -> RuntimeCall("require"),
  QualifiedName("nanoTime")   -> RuntimeCall("nanoTime"),
  QualifiedName("getenv")     -> RuntimeCall("getenv"),
  QualifiedName("doc")        -> RuntimeCall("doc"),
  QualifiedName("render")     -> RuntimeCall("render"),
  QualifiedName("Some")       -> RuntimeCall("Some"),
  QualifiedName("List")       -> RuntimeCall("List"),
  QualifiedName("Map")        -> RuntimeCall("Map"),
  QualifiedName("math.sqrt")  -> RuntimeCall("Math.sqrt"),
  QualifiedName("math.abs")   -> RuntimeCall("Math.abs"),
  QualifiedName("math.pow")   -> RuntimeCall("Math.pow"),
  QualifiedName("math.floor") -> RuntimeCall("Math.floor"),
  QualifiedName("math.ceil")  -> RuntimeCall("Math.ceil"),
  QualifiedName("math.round") -> RuntimeCall("Math.round"),
  QualifiedName("escape")     -> RuntimeCall("escape"),
  QualifiedName("collectCss") -> RuntimeCall("collectCss"),
  QualifiedName("collectJs")  -> RuntimeCall("collectJs"),
  QualifiedName("scope")      -> RuntimeCall("scope"),
)
