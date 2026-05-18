package scalascript.codegen

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** WebSocket server intrinsics for the JVM (Scala-CLI) backend (Stage 5+/D). */
val JvmWsIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("metrics")              -> RuntimeCall("metrics"),
  QualifiedName("setMaxWsConnections")  -> RuntimeCall("setMaxWsConnections"),
  QualifiedName("WsRoom")               -> RuntimeCall("WsRoom"),
)
