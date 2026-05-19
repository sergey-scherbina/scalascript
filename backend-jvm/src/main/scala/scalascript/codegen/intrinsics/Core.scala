package scalascript.codegen.jvm

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Core language intrinsics for the JVM (Scala-CLI) backend (Stage 5+/E). */
val JvmCoreIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("assert")     -> RuntimeCall("assert"),
  QualifiedName("require")    -> RuntimeCall("require"),
  QualifiedName("nanoTime")   -> RuntimeCall("java.lang.System.nanoTime"),
  QualifiedName("getenv")     -> RuntimeCall("getenv"),
  QualifiedName("doc")        -> RuntimeCall("doc"),
  QualifiedName("render")     -> RuntimeCall("render"),
  QualifiedName("Some")       -> RuntimeCall("Some"),
  QualifiedName("List")       -> RuntimeCall("List"),
  QualifiedName("Map")        -> RuntimeCall("Map"),
  QualifiedName("math.sqrt")  -> RuntimeCall("math.sqrt"),
  QualifiedName("math.abs")   -> RuntimeCall("math.abs"),
  QualifiedName("math.pow")   -> RuntimeCall("math.pow"),
  QualifiedName("math.floor") -> RuntimeCall("math.floor"),
  QualifiedName("math.ceil")  -> RuntimeCall("math.ceil"),
  QualifiedName("math.round") -> RuntimeCall("math.round"),
  QualifiedName("escape")     -> RuntimeCall("escape"),
  QualifiedName("collectCss") -> RuntimeCall("collectCss"),
  QualifiedName("collectJs")  -> RuntimeCall("collectJs"),
  QualifiedName("scope")      -> RuntimeCall("scope"),
)
